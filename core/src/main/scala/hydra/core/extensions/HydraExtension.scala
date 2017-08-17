/*
 * Copyright (C) 2016 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package hydra.core.extensions

import akka.actor._
import com.typesafe.config.Config
import hydra.common.logging.LoggingAdapter

import scala.concurrent.Future

/**
  * Hydra typed modules are a custom way to add functionality to Hydra Core.
  *
  * They are instantiated as TypedActors, where as HydraModules are instantiated as regular
  * Akka actors.
  *
  * Modules are managed by Akka extensions.
  *
  * Created by alexsilva on 12/17/15.
  */
sealed trait BaseHydraExtension {

  /**
    * Must be unique.
    *
    * @return
    */
  def id: String

  def context: ActorContext

  def config: Config

  /**
    * Runs the module functionality.
    *
    * @throws java.lang.Exception
    */
  @throws(classOf[Exception])
  def run(): Unit
}

trait HydraTypedExtension extends BaseHydraExtension with LoggingAdapter {

  override val context: ActorContext = TypedActor.context

  /**
    * The equivalent of preStart
    *
    * @throws java.lang.Exception
    * @return
    */
  @throws(classOf[Exception])
  def init(): Future[Boolean] = Future.successful(true)

  /**
    * The equivalent of postStop
    *
    * @throws java.lang.Exception
    * @return
    */
  @throws(classOf[Exception])
  def stop(): Unit = {}

}

trait HydraExtension extends Actor with BaseHydraExtension with LoggingAdapter

object HydraExtension {
  case object Run
}