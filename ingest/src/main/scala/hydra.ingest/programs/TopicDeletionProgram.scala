package hydra.ingest.programs

import cats.MonadError
import hydra.avro.registry.SchemaRegistry
import hydra.avro.registry.SchemaRegistry.SchemaVersion
import hydra.kafka.algebras.KafkaAdminAlgebra.KafkaDeleteTopicErrorList
import hydra.kafka.algebras.KafkaAdminAlgebra
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._
import hydra.ingest.programs.TopicDeletionProgram.{FailureToDeleteSchemaVersion, FailureToGetSchemaVersions, SchemaDeleteTopicErrorList, SchemaRegistryError}


final class TopicDeletionProgram[F[_]: MonadError[*[_], Throwable]](kafkaClient: KafkaAdminAlgebra[F],
                                              schemaClient: SchemaRegistry[F]) {

  def deleteFromSchemaRegistry(topicNames: List[String]): F[ValidatedNel[SchemaRegistryError, Unit]] = {
    topicNames.flatMap(topic => List(topic + "-key", topic + "-value")).traverse { subject =>
      schemaClient.getAllVersions(subject).attempt.flatMap {
        case Right(versions) =>
          versions.traverse(version => schemaClient.deleteSchemaOfVersion(subject, version)
          .attempt.map(_.leftMap(cause => FailureToDeleteSchemaVersion(version, subject, cause): SchemaRegistryError).toValidatedNel))
        case Left(error) =>
          val e: SchemaRegistryError = FailureToGetSchemaVersions(subject, error)
          List(e.invalidNel[Unit]).pure[F]
      }
    }.map(_.flatten.combineAll)
  }

  def deleteTopic(topicNames: List[String]): F[ValidatedNel[DeleteTopicError, Unit]] = {
    kafkaClient.deleteTopics(topicNames).flatMap { result =>
      val topicsToDeleteSchemaFor = result match {
        case Right(_) => topicNames
        case Left(error) =>
          val failedTopicNames = error.errors.map(_.topicName).toList.toSet
          topicNames.toSet.diff(failedTopicNames).toList
      }
      deleteFromSchemaRegistry(topicsToDeleteSchemaFor).map(schemaResult =>
        schemaResult.toEither.leftMap(a => SchemaDeletionErrors(SchemaDeleteTopicErrorList(a)))
          .toValidatedNel.combine(result.leftMap(KafkaDeletionErrors).toValidatedNel)
      )
    }
  }
}

object TopicDeletionProgram {

  sealed abstract class SchemaRegistryError(subject: String, message: String, cause: Throwable) extends RuntimeException(message, cause) {
    def errorMessage: String = s"$message $cause"
    def getSubject: String = subject
  }

  final case class FailureToGetSchemaVersions(subject: String, cause: Throwable)
    extends SchemaRegistryError(subject, s"Unable to get all schema versions for $subject", cause)

  final case class FailureToDeleteSchemaVersion(schemaVersion: SchemaVersion, subject: String, cause: Throwable)
    extends SchemaRegistryError(subject, s"Failed to delete version: $schemaVersion for $subject", cause)

  final case class SchemaDeleteTopicErrorList(errors: NonEmptyList[SchemaRegistryError])
    extends Exception (s"Topic(s) failed to delete:\n${errors.map(_.errorMessage).toList.mkString("\n")}")
}

sealed abstract class DeleteTopicError extends RuntimeException
final case class KafkaDeletionErrors(kafkaDeleteTopicErrorList: KafkaDeleteTopicErrorList) extends DeleteTopicError
final case class SchemaDeletionErrors(schemaDeleteTopicErrorList: SchemaDeleteTopicErrorList) extends DeleteTopicError
