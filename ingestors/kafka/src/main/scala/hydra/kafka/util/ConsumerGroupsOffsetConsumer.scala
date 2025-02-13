package hydra.kafka.util

import java.nio.ByteBuffer
import java.time.Instant
import cats.{Applicative, ApplicativeError, Order, data}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Sync, Timer}
import cats.implicits._
import fs2.Chunk.Bytes
import fs2.kafka._
import hydra.avro.registry.SchemaRegistry
import hydra.kafka.algebras.ConsumerGroupsAlgebra.PartitionOffsetMap
import hydra.kafka.algebras.KafkaClientAlgebra.{OffsetInfo, Record}
import hydra.kafka.algebras.{KafkaAdminAlgebra, KafkaClientAlgebra}
import hydra.kafka.model.TopicConsumer.{TopicConsumerKey, TopicConsumerValue}
import hydra.kafka.model.TopicConsumerOffset.{TopicConsumerOffsetKey, TopicConsumerOffsetValue}
import hydra.kafka.model.TopicMetadataV2Request.Subject
import hydra.kafka.model.{TopicConsumer, TopicConsumerOffset}
import io.chrisdavenport.log4cats.Logger
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.serializers.{AbstractKafkaAvroSerDeConfig, KafkaAvroSerializer}
import kafka.common.OffsetAndMetadata
import kafka.coordinator.group.{BaseKey, GroupMetadataManager, OffsetKey}
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.common.TopicPartition

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.util.Try

object ConsumerGroupsOffsetConsumer {

  var myMap = (0 to 50).map((_, 0L)).toMap

  def start[F[_]: ContextShift: ConcurrentEffect: Timer: Logger](
                                                                  kafkaClientAlgebra: KafkaClientAlgebra[F],
                                                                  kafkaAdminAlgebra: KafkaAdminAlgebra[F],
                                                                  schemaRegistryAlgebra: SchemaRegistry[F],
                                                                  uniquePerNodeConsumerGroup: String,
                                                                  consumerOffsetsOffsetsTopicConfig: Subject,
                                                                  kafkaInternalTopic: String,
                                                                  dvsConsumersTopic: Subject,
                                                                  bootstrapServers: String,
                                                                  commonConsumerGroup: String
                                                                ): F[Unit] = {
    val dvsConsumerOffsetStream = kafkaClientAlgebra.consumeMessagesWithOffsetInfo(consumerOffsetsOffsetsTopicConfig.value, uniquePerNodeConsumerGroup, commitOffsets = false)

    for {
      schemaRegistryClient <- schemaRegistryAlgebra.getSchemaRegistryClient
      deferred <- Deferred[F, PartitionOffsetMap]
      hydraConsumerOffsetsOffsetsLatestOffsets <- kafkaAdminAlgebra.getLatestOffsets(consumerOffsetsOffsetsTopicConfig.value).map(_.map(l => l._1.partition -> l._2.value))
      hydraConsumerOffsetsOffsetsCache <- Ref[F].of[PartitionOffsetMap](hydraConsumerOffsetsOffsetsLatestOffsets.filter(_._2 == 0))
      initialPartitionCache <- initializePartitions(kafkaAdminAlgebra, kafkaInternalTopic)
      backgroundProcess <- Concurrent[F].start(getOffsetsToSeekTo(initialPartitionCache, deferred, dvsConsumerOffsetStream,hydraConsumerOffsetsOffsetsLatestOffsets, hydraConsumerOffsetsOffsetsCache))
      partitionMap <- deferred.get
      _ <- backgroundProcess.cancel
      _ <- Concurrent[F].start(consumerOffsetsToInternalOffsets(kafkaInternalTopic, dvsConsumersTopic.value, bootstrapServers, commonConsumerGroup, schemaRegistryClient, partitionMap, consumerOffsetsOffsetsTopicConfig.value))
    } yield ()
  }

  // Gets partition info for the kafkaInternalTopic "__consumer_offsets" and initializes each to offset to 0
  private def initializePartitions[F[_]: ConcurrentEffect](kafkaAdminAlgebra: KafkaAdminAlgebra[F], kafkaInternalTopic: String): F[Ref[F, PartitionOffsetMap]] = {
    for {
      consumerOffsetsLatestOffsets <- kafkaAdminAlgebra.getLatestOffsets(kafkaInternalTopic).map { m =>
        m.map(p => (p._1.partition, 0L))
      }
      cache <- Ref[F].of(consumerOffsetsLatestOffsets)
    } yield cache
  }

  private def getConsumerGroupDeserializer[F[_]: Sync, A](byteBufferToA: ByteBuffer => A): Deserializer[F, Option[A]] =
    Deserializer.delegate[F, Option[A]] {
      (_: String, data: Array[Byte]) => {
        Try(byteBufferToA(Bytes(data).toByteBuffer)).toOption
      }
    }.suspend

  private def getSerializer[F[_]: Sync, A](schemaRegistryClient: SchemaRegistryClient)(isKey: Boolean): Serializer[F, A] =
    Serializer.delegate[F, A] {
      val serializer = {
        val se = new KafkaAvroSerializer(schemaRegistryClient)
        se.configure(Map(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG -> "").asJava, isKey)
        se
      }
      (topic: String, data: A) => serializer.serialize(topic, data)
    }.suspend

  private def processRecord[F[_]: ConcurrentEffect: Logger](
                                                             cr: CommittableConsumerRecord[F, Option[BaseKey], Option[OffsetAndMetadata]],
                                                             destinationTopic: String,
                                                             dvsInternalKafkaOffsetTopic: String,
                                                             keySerializer: Serializer[F, GenericRecord],
                                                             valueSerializer: Serializer[F, GenericRecord]
                                                           ): fs2.Stream[F, ProducerRecords[Array[Byte], Array[Byte], Unit]] = {
    ((cr.record.key, cr.record.value) match {
      case (Some(OffsetKey(_, groupTopicPartition)), offsetMaybe) =>
        val maybeK = Option(groupTopicPartition)
        val topicMaybe: Option[String] = maybeK.flatMap(k => Option(k.topicPartition).flatMap(tp => Option(tp.topic())))
        val consumerGroupMaybe: Option[String] = maybeK.flatMap(k => Option(k.group))
        val consumerKeyMaybe: Option[TopicConsumerKey] = consumerGroupMaybe.flatMap(cg => topicMaybe.map(t => TopicConsumerKey(t, cg)))
        val consumerValue = offsetMaybe.map(o => Instant.ofEpochMilli(o.commitTimestamp)).map(TopicConsumerValue.apply)

        consumerKeyMaybe match {
          case Some(consumerKey) =>
            fs2.Stream.eval(for {
              topicConsumer <- TopicConsumer.encode[F](consumerKey, consumerValue)
              topicConsumerOffset <- TopicConsumerOffset.encode[F](
                TopicConsumerOffsetKey(cr.offset.topicPartition.topic(),cr.offset.topicPartition.partition()),
                TopicConsumerOffsetValue(cr.offset.offsetAndMetadata.offset())
              )
              (key, value) = topicConsumer
              (offsetKey, offsetValue) = topicConsumerOffset
              k <- keySerializer.serialize(destinationTopic, Headers.empty, key)
              v <- valueSerializer.serialize(destinationTopic, Headers.empty, value.orNull)
              offsetK <- keySerializer.serialize(dvsInternalKafkaOffsetTopic, Headers.empty, offsetKey)
              offsetV <- valueSerializer.serialize(dvsInternalKafkaOffsetTopic, Headers.empty, offsetValue)
            } yield  {
              val p = ProducerRecord(destinationTopic, k, v)
              val p2 = ProducerRecord(dvsInternalKafkaOffsetTopic, offsetK, offsetV)
              ProducerRecords(List(p, p2))
            })
          case None =>
            fs2.Stream.empty
        }
      case _ =>
        fs2.Stream.empty
    }).handleErrorWith { error =>
      fs2.Stream.eval(Logger[F].warn(s"DVSConsumerStreamFailure: ${error.getMessage}")) *> fs2.Stream.empty
    }
  }


  private def consumerOffsetsToInternalOffsets[F[_]: ConcurrentEffect: ContextShift: Timer: Logger]
  (
    sourceTopic: String,
    destinationTopic: String,
    bootstrapServers: String,
    consumerGroupName: String,
    s: SchemaRegistryClient,
    partitionMap: PartitionOffsetMap,
    dvsInternalKafkaOffsetTopic: String
  ) = {
    val settings: ConsumerSettings[F, Option[BaseKey], Option[OffsetAndMetadata]] = ConsumerSettings(
      getConsumerGroupDeserializer[F, BaseKey](GroupMetadataManager.readMessageKey),
      getConsumerGroupDeserializer[F, OffsetAndMetadata](GroupMetadataManager.readOffsetMessageValue)
    )
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(consumerGroupName)

    val producerSettings = ProducerSettings[F, Array[Byte], Array[Byte]]
      .withBootstrapServers(bootstrapServers)
      .withRetries(0)
      .withAcks(Acks.One)
    val consumer = consumerStream(settings)
    val keySerializer = getSerializer[F, GenericRecord](s)(isKey = true)
    val valueSerializer = getSerializer[F, GenericRecord](s)(isKey = false)

    seekToLatestOffsets(sourceTopic)(consumer, partitionMap)
      .flatMap(_.stream)
      .flatMap { cr =>
        processRecord(
          cr,
          destinationTopic,
          dvsInternalKafkaOffsetTopic,
          keySerializer,
          valueSerializer
        )
      }
      .through(produce(producerSettings))
      .recoverWith{
        case e =>
          logStreamError(e) *>
            fs2.Stream.empty
      }
      .compile.drain
  }


  private def seekToLatestOffsets[F[_]: ConcurrentEffect : Logger](sourceTopic: String)
                                                                  (
                                                                    stream: fs2.Stream[F, KafkaConsumer[F, Option[BaseKey], Option[OffsetAndMetadata]]],
                                                                    p: PartitionOffsetMap
                                                                  ): fs2.Stream[F, KafkaConsumer[F, Option[BaseKey], Option[OffsetAndMetadata]]] = {
    implicit val order: Order[TopicPartition] =
      (x: TopicPartition, y: TopicPartition) => if (x.partition() > y.partition()) 1 else if (x.partition() < y.partition()) -1 else 0
    stream.flatTap { b =>
      fs2.Stream.eval(
        if (p.nonEmpty) {
          val topicPartitionList = p.iterator.map(_._1).map(new TopicPartition(sourceTopic, _)).toList
          val topicPartitions = data.NonEmptySet.of[TopicPartition](topicPartitionList.head, topicPartitionList.tail:_*)
          b.assign(topicPartitions).recoverWith { case e =>
            Logger[F].error(s"AssignToTopic for __consumer_offsets Error: ${e.getMessage}") *> ConcurrentEffect[F].unit
          } *>
            p.iterator.toList.traverse { case (p, o) =>
              b.seek(new TopicPartition(sourceTopic, p), o).recoverWith { case e =>
                Logger[F].error(s"SeekToOffset for __consumer_offsets Error: ${e.getMessage}") *> ConcurrentEffect[F].unit
              }
            }.flatMap(_ => Applicative[F].unit)
        } else {
          b.subscribeTo(sourceTopic)
        }
      )
    }
  }


  private def logStreamError[F[_]: Logger](e: Throwable): fs2.Stream[F, Unit] = {
    val errorMessage = s"Error in ConsumerGroupsOffsetConsumer Error: ${e.getMessage}"
    fs2.Stream.eval(Logger[F].error(errorMessage))
  }

  private[kafka] def getOffsetsToSeekTo[F[_]: ConcurrentEffect: Logger](
                                                                 consumerOffsetsCache: Ref[F, PartitionOffsetMap],
                                                                 deferred: Deferred[F, PartitionOffsetMap],
                                                                 dvsConsumerOffsetStream: fs2.Stream[F, (Record, OffsetInfo)],
                                                                 hydraConsumerOffsetsOffsetsLatestOffsets: PartitionOffsetMap,
                                                                 hydraConsumerOffsetsOffsetsCache: Ref[F, PartitionOffsetMap]
                                                               ): F[Unit] = {
    def onStart = if (hydraConsumerOffsetsOffsetsLatestOffsets.values.forall(_ == 0L)) deferred.complete(Map()) else ConcurrentEffect[F].unit
    def isComplete: F[Unit] = for {
      consumerOffsets <- consumerOffsetsCache.get
      hydraConsumerOffsetsOffsets <- hydraConsumerOffsetsOffsetsCache.get
      isFulfilled = hydraConsumerOffsetsOffsetsLatestOffsets.forall(p => hydraConsumerOffsetsOffsets.getOrElse(p._1, 0L) == p._2)
      _ <- if (isFulfilled) {
        deferred.complete(consumerOffsets)
      } else {
        ConcurrentEffect[F].unit
      }
    } yield ()

    onStart *> dvsConsumerOffsetStream.flatMap { case ((key, value, _), (partition, offset)) =>
      fs2.Stream.eval(TopicConsumerOffset.decode[F](key, value).flatMap { case (topicKey, topicValue) =>
        consumerOffsetsCache.update(_ + (topicKey.partition -> topicValue.get.offset)) *>
          hydraConsumerOffsetsOffsetsCache.update(_ + (partition -> (offset + 1L)))
      }).flatTap { _ =>
        fs2.Stream.eval(isComplete)
      }
    }.recoverWith {
      case e =>
        logStreamError(e) *> fs2.Stream.empty
    }.compile.drain
  }
}
