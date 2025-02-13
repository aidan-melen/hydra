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

package hydra.ingest.http

import akka.actor.{ActorRef, ActorSelection, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.pattern.ask
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import hydra.avro.resource.SchemaResource
import hydra.common.config.ConfigSupport
import hydra.common.config.ConfigSupport._
import hydra.core.akka.SchemaRegistryActor
import hydra.core.akka.SchemaRegistryActor._
import hydra.core.http.{CorsSupport, DefaultCorsSupport, RouteSupport}
import hydra.core.marshallers.GenericServiceResponse
import hydra.core.monitor.HydraMetrics.addHttpMetric
import hydra.kafka.consumer.KafkaConsumerProxy.{ListTopics, ListTopicsResponse}
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import org.apache.avro.SchemaParseException
import org.apache.kafka.common.PartitionInfo
import scalacache.cachingF
import scalacache.guava.GuavaCache
import scalacache.modes.scalaFuture._
import spray.json.RootJsonFormat

import java.time.Instant
import hydra.kafka.services.StreamsManagerActor.{GetMetadata, GetMetadataResponse}

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * A wrapper around Confluent's schema registry that facilitates schema registration and retrieval.
  *
  * Created by alexsilva on 2/13/16.
  */
class SchemasEndpoint(consumerProxy: ActorSelection, streamsManagerActor: ActorRef)(implicit system: ActorSystem, corsSupport: CorsSupport)
    extends RouteSupport
    with ConfigSupport
    with DefaultCorsSupport {
  private implicit val cache = GuavaCache[Map[String, Seq[PartitionInfo]]]


  implicit val endpointFormat: RootJsonFormat[SchemasEndpointResponse] = jsonFormat3(SchemasEndpointResponse.apply)
  implicit val v2EndpointFormat: RootJsonFormat[SchemasWithKeyEndpointResponse] = jsonFormat2(SchemasWithKeyEndpointResponse.apply)
  implicit val schemasWithTopicFormat: RootJsonFormat[SchemasWithTopicResponse] = jsonFormat2(SchemasWithTopicResponse.apply)
  implicit val batchSchemasFormat: RootJsonFormat[BatchSchemasResponse] = {
    val make: List[SchemasWithTopicResponse] => BatchSchemasResponse = BatchSchemasResponse.apply
    jsonFormat1(make)
  }
  implicit val timeout: Timeout = Timeout(3.seconds)

  private val schemaRegistryActor =
    system.actorOf(SchemaRegistryActor.props(applicationConfig))

  private val filterSystemTopics = (t: String) =>
    (t.startsWith("_") && showSystemTopics) || !t.startsWith("_")

  private val showSystemTopics = applicationConfig
    .getBooleanOpt("transports.kafka.show-system-topics")
    .getOrElse(false)

  override def route: Route = cors(corsSupport.settings) {
    extractMethod { method =>
      handleExceptions(excptHandler(Instant.now, method.value)) {
        extractExecutionContext { implicit ec =>
          pathPrefix("schemas") {
            val startTime = Instant.now
            get {
              pathEndOrSingleSlash {
                onSuccess(
                  (schemaRegistryActor ? FetchSubjectsRequest)
                    .mapTo[FetchSubjectsResponse]
                ) { response =>
                  addHttpMetric("", OK, "/schemas", startTime, method.value)
                  complete(OK, response.subjects)
                }
              } ~ path(Segment) { subject =>
                parameters('schema ?) { schemaOnly: Option[String] =>
                  getSchema(includeKeySchema = false, subject, schemaOnly, startTime)
                }
              } ~ path(Segment / "versions") { subject =>
                onSuccess(
                  (schemaRegistryActor ? FetchAllSchemaVersionsRequest(subject))
                    .mapTo[FetchAllSchemaVersionsResponse]
                ) { response =>
                  addHttpMetric(subject, OK, "/schemas/.../versions", startTime, method.value)
                  complete(OK, response.versions.map(SchemasEndpointResponse(_)))
                }
              } ~ path(Segment / "versions" / IntNumber) { (subject, version) =>
                onSuccess(
                  (schemaRegistryActor ? FetchSchemaVersionRequest(
                    subject,
                    version
                  )).mapTo[FetchSchemaVersionResponse]
                ) { response =>
                  addHttpMetric(subject, OK, "/schemas/.../versions/" + version, startTime, method.value)
                  complete(OK, SchemasEndpointResponse(response.schemaResource))
                }
              }
            } ~
              post {
                registerNewSchema(startTime)
              }
          } ~ v2Route
        }
      }
    }
  }

  private val v2Route = {
    pathPrefix("v2") {
      get {
        pathPrefix("schemas") {
          val startTime = Instant.now
          pathEndOrSingleSlash {
            extractExecutionContext { implicit ec =>
              onSuccess((streamsManagerActor ? GetMetadata).mapTo[GetMetadataResponse].map(_.metadata.keys.toList))  { keyList =>
                  getSchemas(keyList, startTime)
              }
            }
          }
        } ~
        pathPrefix("schemas" / Segment) { subject =>
          pathEndOrSingleSlash {
            val startTime = Instant.now
            getSchema(includeKeySchema = true, subject, None, startTime)
          }
        }
      }
    }
  }

  def getSchema(includeKeySchema: Boolean, subject: String, schemaOnly: Option[String], startTime: Instant): Route = {
    onSuccess(
      (schemaRegistryActor ? FetchSchemaRequest(subject))
        .mapTo[FetchSchemaResponse]
    ) { response =>
      extractExecutionContext { implicit ec =>
        if (includeKeySchema) {
          addHttpMetric(subject, OK, "/v2/schemas/", startTime, "GET")
          complete(OK, SchemasWithKeyEndpointResponse.apply(response))
        } else {
          val schemaResource = response.schemaResource
          addHttpMetric(subject, OK, "/schema/", startTime, "GET")
          schemaOnly.map{_ =>
            complete(OK, schemaResource.schema.toString)}
            .getOrElse {
              complete(OK, SchemasEndpointResponse(schemaResource))
            }
        }
      }
    }
  }

  def getSchemas(subjects: List[String], startTime: Instant): Route = {
    val filteredSubjects = subjects.filterNot(_.contains("cp-kafka-co"))
    onSuccess {
      (schemaRegistryActor ? FetchSchemasRequest(filteredSubjects)).mapTo[FetchSchemasResponse]
    } {
      response => {
        extractExecutionContext { implicit ec =>
          addHttpMetric("", OK , "/schemas", startTime, "GET")
          complete(OK, BatchSchemasResponse.apply(response))
        }
      }
    }
  }

  private def registerNewSchema(startTime: Instant): Route = {
    entity(as[String]) { json =>
      extractExecutionContext { implicit ec =>
        extractRequest { request =>
          onSuccess(
            (schemaRegistryActor ? RegisterSchemaRequest(json))
              .mapTo[RegisterSchemaResponse]
          ) { registeredSchema =>
            respondWithHeader(
              Location(
                request.uri.copy(path =
                  request.uri.path / registeredSchema.schemaResource.schema.getFullName
                )
              )
            ) {
              addHttpMetric(registeredSchema.schemaResource.schema.getFullName, Created, "/schemas", startTime, "POST")
              complete(Created, SchemasEndpointResponse(registeredSchema))
            }
          }
        }
      }
    }
  }

  private[http] def excptHandler(startTime: Instant, method: String): ExceptionHandler = ExceptionHandler {
    case e: RestClientException if e.getErrorCode == 40401 =>
      extractExecutionContext { implicit ec =>
        addHttpMetric("", NotFound, "schemasEndpoint", startTime, method, error = Some(e.getMessage))
        complete(NotFound, GenericServiceResponse(404, e.getMessage))
      }

    case e: RestClientException =>
      val registryHttpStatus = e.getStatus
      val registryErrorCode = e.getErrorCode
      extractExecutionContext { implicit ec =>
        addHttpMetric("", registryHttpStatus, "schemasEndpoint", startTime, method, error = Some(s"Rest Client Exception - $e.getMessage"))
        complete(
          registryHttpStatus,
          GenericServiceResponse(
            registryErrorCode,
            s"Registry error: ${e.getMessage}"
          )
        )
      }

    case e: SchemaParseException =>
      extractExecutionContext { implicit ec =>
        addHttpMetric("", BadRequest, "schemasEndpoint", startTime, method, error = Some(s"Schema Parse Exception - ${e.getMessage}"))
        complete(
          BadRequest,
          GenericServiceResponse(
            400,
            s"Unable to parse avro schema: ${e.getMessage}"
          )
        )
      }

    case e: Exception =>
      extractExecutionContext { implicit ec =>
        extractUri { uri =>
          log.warn(s"Request to $uri failed with exception: {}", e)
          addHttpMetric("", BadRequest, "schemasEndpoint", startTime, method, error = Some(e.getMessage))
          complete(
            BadRequest,
            GenericServiceResponse(
              400,
              s"Unable to complete request for ${uri.path.tail} : ${e.getMessage}"
            )
          )
        }
      }
  }

  private def topics(implicit ec: ExecutionContext): Future[Map[String, Seq[PartitionInfo]]] = {
    implicit val timeout = Timeout(5 seconds)
    cachingF("topics")(ttl = Some(1.minute)) {
      import akka.pattern.ask
      (consumerProxy ? ListTopics).mapTo[ListTopicsResponse].map { response =>
        response.topics.filter(t => filterSystemTopics(t._1)).map {
          case (k, v) => k -> v.toList
        }
      }
    }
  }
}

case class SchemasWithKeyEndpointResponse(keySchemaResponse: Option[SchemasEndpointResponse], valueSchemaResponse: SchemasEndpointResponse)

object SchemasWithKeyEndpointResponse {
  def apply(f: FetchSchemaResponse): SchemasWithKeyEndpointResponse =
    new SchemasWithKeyEndpointResponse(
      f.keySchemaResource.map(SchemasEndpointResponse.apply),
      SchemasEndpointResponse.apply(f.schemaResource)
    )
}

case class SchemasWithTopicResponse(topic: String, valueSchemaResponse: Option[SchemasEndpointResponse])

object SchemasWithTopicResponse {
  def apply(t: (String, Option[SchemaResource])): SchemasWithTopicResponse =
    new SchemasWithTopicResponse(t._1, t._2.map(SchemasEndpointResponse.apply))
}

case class BatchSchemasResponse(schemasResponse: List[SchemasWithTopicResponse])

object BatchSchemasResponse {
  def apply(f: FetchSchemasResponse) =
    new BatchSchemasResponse(f.valueSchemas.map(SchemasWithTopicResponse.apply))
}

case class SchemasEndpointResponse(id: Int, version: Int, schema: String)

object SchemasEndpointResponse {

  def apply(resource: SchemaResource): SchemasEndpointResponse =
    SchemasEndpointResponse(
      resource.id,
      resource.version,
      resource.schema.toString
    )

  def apply(
      registeredSchema: SchemaRegistryActor.RegisterSchemaResponse
  ): SchemasEndpointResponse = {
    val resource = registeredSchema.schemaResource
    SchemasEndpointResponse(
      resource.id,
      resource.version,
      resource.schema.toString
    )
  }
}
