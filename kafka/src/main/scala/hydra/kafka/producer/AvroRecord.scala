package hydra.kafka.producer

import com.pluralsight.hydra.avro.JsonConverter
import hydra.core.produce.RetryStrategy.Fail
import hydra.core.produce.{AckStrategy, RetryStrategy}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord

/**
  * Created by alexsilva on 10/30/15.
  */
case class AvroRecord(destination: String, schema: Schema, key: Option[String], json: String,
                      retryStrategy: RetryStrategy = Fail) extends KafkaRecord[String, GenericRecord] {

  val payload: GenericRecord = {
    val converter: JsonConverter[GenericRecord] = new JsonConverter[GenericRecord](schema)
    converter.convert(json)
  }
}
