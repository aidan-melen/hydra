package hydra.ingest.app

import cats.implicits._
import ciris.{ConfigValue, env, _}
import hydra.kafka.model.ContactMethod
import hydra.kafka.model.TopicMetadataV2Request.Subject

import scala.concurrent.duration._

object AppConfig {

  final case class SchemaRegistryConfig(
      fullUrl: String,
      maxCacheSize: Int
  )

  private val schemaRegistryConfig: ConfigValue[SchemaRegistryConfig] =
    (
      env("HYDRA_SCHEMA_REGISTRY_URL")
        .as[String]
        .default("http://localhost:8081"),
      env("HYDRA_MAX_SCHEMAS_PER_SUBJECT").as[Int].default(1000)
    ).parMapN(SchemaRegistryConfig)

  final case class CreateTopicConfig(
      schemaRegistryConfig: SchemaRegistryConfig,
      numRetries: Int,
      baseBackoffDelay: FiniteDuration,
      bootstrapServers: String,
      defaultNumPartions: Int,
      defaultReplicationFactor: Short
  )

  private val createTopicConfig: ConfigValue[CreateTopicConfig] =
    (
      schemaRegistryConfig,
      env("CREATE_TOPIC_NUM_RETRIES").as[Int].default(1),
      env("CREATE_TOPIC_BASE_BACKOFF_DELAY")
        .as[FiniteDuration]
        .default(1.second),
      env("HYDRA_KAFKA_PRODUCER_BOOTSTRAP_SERVERS").as[String],
      env("HYDRA_DEFAULT_PARTIONS").as[Int].default(10),
      env("HYDRA_REPLICATION_FACTOR").as[Short].default(3)
    ).parMapN(CreateTopicConfig)

  private implicit val subjectConfigDecoder: ConfigDecoder[String, Subject] =
    ConfigDecoder.identity[String].mapOption("Subject")(Subject.createValidated)

  final case class V2MetadataTopicConfig(
      topicName: Subject,
      createOnStartup: Boolean,
      createV2TopicsEnabled: Boolean,
      contactMethod: ContactMethod,
      numPartitions: Int,
      replicationFactor: Short
  )

  private implicit def contactMethodDecoder
      : ConfigDecoder[String, ContactMethod] =
    ConfigDecoder
      .identity[String]
      .mapOption("ContactMethod")(ContactMethod.create)

  private val v2MetadataTopicConfig: ConfigValue[V2MetadataTopicConfig] =
    (
      env("HYDRA_V2_METADATA_TOPIC_NAME")
        .as[Subject]
        .default(Subject.createValidated("_hydra.v2.metadata").get),
      env("HYDRA_V2_METADATA_CREATE_ON_STARTUP").as[Boolean].default(false),
      env("HYDRA_V2_CREATE_TOPICS_ENABLED").as[Boolean].default(false),
      env(
        "HYDRA_V2_METADATA_CONTACT"
      ).as[ContactMethod],
      env("HYDRA_DEFAULT_PARTIONS").as[Int].default(10),
      env("HYDRA_REPLICATION_FACTOR").as[Short].default(3)
    ).parMapN(V2MetadataTopicConfig)

  final case class AppConfig(
      createTopicConfig: CreateTopicConfig,
      v2MetadataTopicConfig: V2MetadataTopicConfig
  )

  val appConfig: ConfigValue[AppConfig] =
    (
      createTopicConfig,
      v2MetadataTopicConfig
    ).parMapN(AppConfig)

}
