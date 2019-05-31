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

package org.apache.spark.scheduler


/**
 * Internal class to expose executor resources information to TaskScheduler from SchedulerBackend,
 * while also ensure the TaskScheduler will not modify the executor resources information.
 * Idealy we shall keep a copy of the map from resource name to resource addresses inside this
 * class, but the deep copy can be time consuming when you have many executors, so we just keep
 * the copy inside class ExecutorResourceInfo.
 */
private[scheduler] class InternalExecutorResourcesInfo(
    private val resources: Map[String, ExecutorResourceInfo]) extends Serializable {

  // Reset all the buffered addresses.
  resources.values.foreach(_.resetIdleAddresses())

  // Number of resource addresses that can be acquired.
  def getNumOfIdleResources(resourceName: String): Int =
    resources.get(resourceName).map(_.getNumOfIdleResources()).getOrElse(0)

  // Acquire given number of resource addresses, these addresses can be assigned to a future
  // launched task.
  def acquireAddresses(resourceName: String, num: Int): Seq[String] = {
    resources.get(resourceName).map(_.acquireAddresses(num)).getOrElse(Seq.empty)
  }
}

private[scheduler] object InternalExecutorResourcesInfo {
  final val EMPTY_RESOURCES_INFO = new InternalExecutorResourcesInfo(Map.empty)
}
