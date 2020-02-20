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

package hydra.kafka.producer

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import hydra.core.ingest.HydraRequest

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by alexsilva on 1/11/17.
  */
object JsonRecordFactory extends KafkaRecordFactory[String, JsonNode] {

  val mapper = new ObjectMapper()

  override def build(request: HydraRequest)
                    (implicit ec: ExecutionContext): Future[KafkaRecord[String, JsonNode]] = {
    for {
      topic <- Future.fromTry(getTopic(request))
      payload <- parseJson(request.payload)
    } yield JsonRecord(topic, getKey(request, payload), payload, request.ackStrategy)

  }

  private def parseJson(json: String)
                       (implicit ec: ExecutionContext) = Future(mapper.reader().readTree(json))

}

