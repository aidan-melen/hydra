package hydra.kafka.endpoints

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.NonEmptyList
import cats.effect.{IO, Timer}
import hydra.avro.registry.SchemaRegistry
import hydra.avro.registry.SchemaRegistry.{SchemaId, SchemaVersion}
import hydra.core.marshallers.History
import hydra.kafka.model.ContactMethod.Email
import hydra.kafka.model.TopicMetadataV2Request.Subject
import hydra.kafka.model._
import hydra.kafka.programs.CreateTopicProgram
import hydra.kafka.serializers.TopicMetadataV2Parser
import hydra.kafka.util.KafkaClient
import hydra.kafka.util.KafkaUtils.TopicDetails
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.apache.avro.{Schema, SchemaBuilder}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import retry.{RetryPolicies, RetryPolicy}

import scala.concurrent.ExecutionContext

final class BootstrapEndpointV2Spec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers {

  private implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  private implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  private def getTestCreateTopicProgram(
      s: SchemaRegistry[IO],
      k: KafkaClient[IO]
  ): BootstrapEndpointV2 = {
    val retryPolicy: RetryPolicy[IO] = RetryPolicies.alwaysGiveUp
    new BootstrapEndpointV2(
      new CreateTopicProgram[IO](
        s,
        k,
        retryPolicy,
        Subject.createValidated("test").get
      ),
      TopicDetails(1, 1)
    )
  }

  private val testCreateTopicProgram: IO[BootstrapEndpointV2] =
    for {
      s <- SchemaRegistry.test[IO]
      k <- KafkaClient.test[IO]
    } yield getTestCreateTopicProgram(s, k)

  "BootstrapEndpointV2" must {

    "reject an empty request" in {
      testCreateTopicProgram
        .map { bootstrapEndpoint =>
          Post("/v2/streams") ~> Route.seal(bootstrapEndpoint.route) ~> check {
            response.status shouldBe StatusCodes.BadRequest
          }
        }
        .unsafeRunSync()
    }

    val getTestSchema: String => Schema = schemaName =>
      SchemaBuilder
        .record(schemaName)
        .fields()
        .name("test")
        .`type`()
        .stringType()
        .noDefault()
        .endRecord()

    val validRequest = TopicMetadataV2Request(
      Subject.createValidated("testing").get,
      Schemas(getTestSchema("key"), getTestSchema("value")),
      History,
      deprecated = false,
      Public,
      NonEmptyList.of(Email.create("test@pluralsight.com").get),
      Instant.now,
      List.empty,
      None
    )

    import TopicMetadataV2Parser._

    "accept a valid request" in {
      testCreateTopicProgram
        .map { bootstrapEndpoint =>
          Post("/v2/streams", validRequest) ~> Route.seal(
            bootstrapEndpoint.route
          ) ~> check {
            response.status shouldBe StatusCodes.OK
          }
        }
        .unsafeRunSync()
    }

    "return an InternalServerError on an unexpected exception" in {
      val failingSchemaRegistry: SchemaRegistry[IO] = new SchemaRegistry[IO] {
        private val err = IO.raiseError(new Exception)
        override def registerSchema(
            subject: String,
            schema: Schema
        ): IO[SchemaId] = err
        override def deleteSchemaOfVersion(
            subject: String,
            version: SchemaVersion
        ): IO[Unit] = err
        override def getVersion(
            subject: String,
            schema: Schema
        ): IO[SchemaVersion] = err
        override def getAllVersions(subject: String): IO[List[Int]] = err
        override def getAllSubjects: IO[List[String]] = err
      }
      KafkaClient
        .test[IO]
        .map { kafka =>
          Post("/v2/streams", validRequest) ~> Route.seal(
            getTestCreateTopicProgram(failingSchemaRegistry, kafka).route
          ) ~> check {
            response.status shouldBe StatusCodes.InternalServerError
          }
        }
        .unsafeRunSync()
    }
  }

}
