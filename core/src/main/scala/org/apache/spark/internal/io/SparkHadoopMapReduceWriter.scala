/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.internal.io

import java.util.Date

import scala.reflect.ClassTag

import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl

import org.apache.spark.{SparkException, TaskContext}
import org.apache.spark.executor.OutputMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.internal.io.FileCommitProtocol.TaskCommitMessage
import org.apache.spark.rdd.RDD
import org.apache.spark.util.{SerializableConfiguration, Utils}

/**
 * A helper object that saves an RDD using a Hadoop OutputFormat
 * (from the newer mapreduce API, not the old mapred API).
 */
private[spark]
object SparkHadoopMapReduceWriter extends Logging {
  import SparkHadoopWriterUtils._

  /**
   * Basic work flow of this command is:
   * 1. Driver side setup, prepare the data source and hadoop configuration for the write job to
   *    be issued.
   * 2. Issues a write job consists of one or more executor side tasks, each of which writes all
   *    rows within an RDD partition.
   * 3. If no exception is thrown in a task, commits that task, otherwise aborts that task;  If any
   *    exception is thrown during task commitment, also aborts that task.
   * 4. If all tasks are committed, commit the job, otherwise aborts the job;  If any exception is
   *    thrown during job commitment, also aborts the job.
   */
  def write[K, V: ClassTag](
      rdd: RDD[(K, V)],
      config: SparkHadoopWriterConfig[K, V],
      hadoopConf: Configuration): Unit = {
    // Extract context and configuration from RDD.
    val sparkContext = rdd.context
    val stageId = rdd.id
    val sparkConf = rdd.conf
    val conf = new SerializableConfiguration(hadoopConf)

    // Set up a job.
    val jobTrackerId = createJobTrackerID(new Date())
    val jobContext = config.createJobContext(jobTrackerId, stageId)
    config.initOutputFormat(jobContext)

    // Assert the output format/key/value class is set in JobConf.
    config.assertConf()

    if (isOutputSpecValidationEnabled(sparkConf)) {
      // FileOutputFormat ignores the filesystem parameter
      config.checkOutputSpecs(jobContext)
    }

    val committer = config.createCommitter(stageId)
    committer.setupJob(jobContext)

    // When speculation is on and output committer class name contains "Direct", we should warn
    // users that they may loss data if they are using a direct output committer.
    if (isSpeculationEnabled(sparkConf) && committer.isDirectOutput) {
      val warningMessage =
        s"$committer may be an output committer that writes data directly to " +
          "the final location. Because speculation is enabled, this output committer may " +
          "cause data loss (see the case in SPARK-10063). If possible, please use an output " +
          "committer that does not have this behavior (e.g. FileOutputCommitter)."
      logWarning(warningMessage)
    }

    // Try to write all RDD partitions as a Hadoop OutputFormat.
    try {
      val ret = sparkContext.runJob(rdd, (context: TaskContext, iter: Iterator[(K, V)]) => {
        executeTask(
          context = context,
          config = config,
          jobTrackerId = jobTrackerId,
          sparkStageId = context.stageId,
          sparkPartitionId = context.partitionId,
          sparkAttemptNumber = context.attemptNumber,
          committer = committer,
          hadoopConf = conf,
          iterator = iter)
      })

      committer.commitJob(jobContext, ret)
      logInfo(s"Job ${jobContext.getJobID} committed.")
    } catch {
      case cause: Throwable =>
        logError(s"Aborting job ${jobContext.getJobID}.", cause)
        committer.abortJob(jobContext)
        throw new SparkException("Job aborted.", cause)
    }
  }

  /** Write an RDD partition out in a single Spark task. */
  private def executeTask[K, V: ClassTag](
      context: TaskContext,
      config: SparkHadoopWriterConfig[K, V],
      jobTrackerId: String,
      sparkStageId: Int,
      sparkPartitionId: Int,
      sparkAttemptNumber: Int,
      committer: FileCommitProtocol,
      hadoopConf: SerializableConfiguration,
      iterator: Iterator[(K, V)]): TaskCommitMessage = {
    // Set up a task.
    val taskContext = config.createTaskAttemptContext(
      jobTrackerId, sparkStageId, sparkPartitionId, sparkAttemptNumber)
    committer.setupTask(taskContext)

    val outputMetricsAndBytesWrittenCallback: Option[(OutputMetrics, () => Long)] =
      initHadoopOutputMetrics(context)

    // Initiate the writer.
    config.initWriter(taskContext, sparkPartitionId)
    var recordsWritten = 0L

    // Write all rows in RDD partition.
    try {
      val ret = Utils.tryWithSafeFinallyAndFailureCallbacks {
        while (iterator.hasNext) {
          val pair = iterator.next()
          config.write(pair)

          // Update bytes written metric every few records
          maybeUpdateOutputMetrics(
            outputMetricsAndBytesWrittenCallback, recordsWritten)
          recordsWritten += 1
        }

        committer.commitTask(taskContext)
      }(catchBlock = {
        committer.abortTask(taskContext)
        logError(s"Task ${taskContext.getTaskAttemptID} aborted.")
      }, finallyBlock = config.closeWriter(taskContext))

      outputMetricsAndBytesWrittenCallback.foreach {
        case (om, callback) =>
          om.setBytesWritten(callback())
          om.setRecordsWritten(recordsWritten)
      }

      ret
    } catch {
      case t: Throwable =>
        throw new SparkException("Task failed while writing rows", t)
    }
  }
}

private[spark]
class SparkHadoopMapReduceWriterConfig[K, V: ClassTag](conf: SerializableConfiguration)
  extends SparkHadoopWriterConfig[K, V] with Serializable with Logging {

  private var outputFormat: Class[_ <: OutputFormat[K, V]] = null
  private var writer: RecordWriter[K, V] = null

  private def getConf(): Configuration = conf.value

  // --------------------------------------------------------------------------
  // Create JobContext/TaskAttemptContext
  // --------------------------------------------------------------------------

  def createJobContext(jobTrackerId: String, jobId: Int): JobContext = {
    val jobAttemptId = new TaskAttemptID(jobTrackerId, jobId, TaskType.MAP, 0, 0)
    new TaskAttemptContextImpl(getConf(), jobAttemptId)
  }

  def createTaskAttemptContext(
      jobTrackerId: String,
      jobId: Int,
      splitId: Int,
      taskAttemptId: Int): TaskAttemptContext = {
    val attemptId = new TaskAttemptID(jobTrackerId, jobId, TaskType.REDUCE, splitId, taskAttemptId)
    new TaskAttemptContextImpl(getConf(), attemptId)
  }

  // --------------------------------------------------------------------------
  // Create committer
  // --------------------------------------------------------------------------

  def createCommitter(jobId: Int): HadoopMapReduceCommitProtocol = {
    FileCommitProtocol.instantiate(
      className = classOf[HadoopMapReduceCommitProtocol].getName,
      jobId = jobId.toString,
      outputPath = getConf().get("mapred.output.dir"),
      isAppend = false).asInstanceOf[HadoopMapReduceCommitProtocol]
  }

  // --------------------------------------------------------------------------
  // Create writer
  // --------------------------------------------------------------------------

  def initWriter(taskContext: TaskAttemptContext, splitId: Int): Unit = {
    val taskFormat = getOutputFormat()
    // If OutputFormat is Configurable, we should set conf to it.
    taskFormat match {
      case c: Configurable => c.setConf(getConf())
      case _ => ()
    }

    writer = taskFormat.getRecordWriter(taskContext)
      .asInstanceOf[RecordWriter[K, V]]

    require(writer != null, "Unable to obtain RecordWriter")
  }

  def write(pair: (K, V)): Unit = {
    require(writer != null, "Must call createWriter before write.")
    writer.write(pair._1, pair._2)
  }

  def closeWriter(taskContext: TaskAttemptContext): Unit = {
    if (writer != null) {
      writer.close(taskContext)
    }
  }

  // --------------------------------------------------------------------------
  // Create OutputFormat
  // --------------------------------------------------------------------------

  def initOutputFormat(jobContext: JobContext): Unit = {
    if (outputFormat == null) {
      outputFormat = jobContext.getOutputFormatClass.asInstanceOf[Class[_ <: OutputFormat[K, V]]]
    }
  }

  private def getOutputFormat(): OutputFormat[K, V] = {
    require(outputFormat != null, "Must call initOutputFormat first.")

    outputFormat.newInstance()
  }

  // --------------------------------------------------------------------------
  // Verify hadoop config
  // --------------------------------------------------------------------------

  def assertConf(): Unit = {
    // Do nothing for mapreduce API.
  }

  def checkOutputSpecs(jobContext: JobContext): Unit = {
    getOutputFormat().checkOutputSpecs(jobContext)
  }

}
