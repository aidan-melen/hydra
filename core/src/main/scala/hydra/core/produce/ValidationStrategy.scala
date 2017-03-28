/*
 * Copyright (C) 2017 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hydra.core.produce

/**
  * Created by alexsilva on 1/11/17.
  */

sealed trait ValidationStrategy

object ValidationStrategy {

  def apply(strategy: String): ValidationStrategy = {
    Option(strategy).map(_.trim.toLowerCase) match {
      case Some(s) if (s.equals("strict")) => Strict
      case Some(s) if (s.equals("relaxed")) => Relaxed
      case None => Strict
    }
  }

  case object Strict extends ValidationStrategy

  case object Relaxed extends ValidationStrategy

}
