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

package hydra.kafka.ingestors

import akka.actor.Timers
import hydra.core.ingest.RequestParams._
import hydra.core.ingest.{HydraRequest, Ingestor, RequestParams}
import hydra.core.protocol._
import hydra.kafka.ingestors.KafkaIngestor.TopicLookupResult
import hydra.kafka.producer.{KafkaProducerSupport, KafkaRecordFactories}
import hydra.kafka.util.KafkaUtils
import org.joda.time.DateTime
import scalacache._
import scalacache.guava._
import scalacache.modes.try_._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}

/**
  * Sends JSON messages to a topic in Kafka.  In order for this handler to be activated.
  * a request param "Hydra-kafka-topic" must be present.
  *
  */
class KafkaIngestor extends Ingestor with KafkaProducerSupport with Timers {

  override val recordFactory = new KafkaRecordFactories(schemaRegistryActor)

  KafkaUtils().topicNames().getOrElse(Seq.empty).foreach(t => KafkaIngestor.cache.put(t)(t))

  ingest {
    case Publish(request) =>
      val hasTopic = request.metadataValue(HYDRA_KAFKA_TOPIC_PARAM).isDefined
      sender ! (if (hasTopic) Join else Ignore)

    case Ingest(record, ackStrategy) => transport(record, ackStrategy)
  }

  override def doValidate(request: HydraRequest): Future[MessageValidationResult] = {
    super.doValidate(request) collect {
      case vr: ValidRequest[_, _] =>
        val tp = request.metadataValue(RequestParams.HYDRA_KAFKA_TOPIC_PARAM).get
        getTopic(tp).map(_.exists).map { e =>
          if (e) vr else InvalidRequest(new IllegalArgumentException(s"Kafka topic '$tp' doesn't exist."))
        }.get

      case iv: InvalidRequest => iv
    }
  }

  private def getTopic(topic: String): Try[TopicLookupResult] = {
    implicit val cache = KafkaIngestor.topicCache
      cache.get(topic) match {
        case Success(result) if result.
        case None =>
      }
  }
}
