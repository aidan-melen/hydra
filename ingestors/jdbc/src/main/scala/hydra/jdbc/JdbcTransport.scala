package hydra.jdbc

import com.typesafe.config.{Config, ConfigFactory, ConfigObject}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import configs.syntax._
import hydra.avro.io.{DeleteByKey, SaveMode, Upsert}
import hydra.avro.util.SchemaWrapper
import hydra.common.config.ConfigSupport
import hydra.common.logging.LoggingAdapter
import hydra.core.transport.Transport
import hydra.core.transport.Transport.Deliver
import hydra.sql.{DataSourceConnectionProvider, JdbcRecordWriter, JdbcWriterSettings, TableIdentifier}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

class JdbcTransport extends Transport with ConfigSupport with LoggingAdapter {

  private[jdbc] val dbProfiles = new mutable.HashMap[String, DbProfile]()

  private val writers = new mutable.HashMap[String, JdbcRecordWriter]()

  override def transport = {
    case Deliver(record: JdbcRecord, deliveryId, callback) =>
      Try {
        val writer = getOrUpdateWriter(dbProfiles(record.dbProfile), record)
        val op = Option(record.payload).map(p => Upsert(p)).getOrElse(DeleteByKey(record.keyValues))
        writer.execute(op)
        callback.onCompletion(deliveryId, Some(JdbcRecordMetadata(record.destination,
          ackStrategy = record.ackStrategy)), None)
      }.recover {
        case e: Exception =>
          callback.onCompletion(deliveryId, None, Some(e))
      }
  }

  private[jdbc] def getOrUpdateWriter(db: DbProfile, rec: JdbcRecord) = {
    //TODO: Make the writer constructor params configurable. Should we support batching?
    val schema = rec.payload.getSchema
    val key = s"${db.name}|${schema.getFullName}"
    writers.getOrElseUpdate(key, new JdbcRecordWriter(db.settings, db.provider,
      SchemaWrapper.from(schema, rec.primaryKeys), SaveMode.Append,
      tableIdentifier = Some(TableIdentifier(rec.destination))))
  }

  override def preStart(): Unit = {
    writers.clear()
    applicationConfig.getOrElse[Config]("transports.jdbc.profiles", ConfigFactory.empty).map { cfg =>
      cfg.root().entrySet().asScala.foreach { e =>
        Try {
          val props = ConfigSupport.toMap(e.getValue.asInstanceOf[ConfigObject].toConfig)
          val settings = JdbcWriterSettings(e.getValue.asInstanceOf[ConfigObject].toConfig)
          dbProfiles.put(e.getKey, new DbProfile(e.getKey, props, settings))
        }.recover { case ex => log.error(s"Unable to load db profile ${e.getKey()}.", ex) }
      }
    }

    log.debug(s"Available database profiles: ${dbProfiles.keySet.mkString(",")}")
  }

  override def postStop(): Unit = {
    Try(writers.foreach(_._2.flush()))
    dbProfiles.foreach(_._2.close())
  }
}

class DbProfile(val name: String, props: Map[String, AnyRef], val settings: JdbcWriterSettings) {

  import ConfigSupport._

  private val hcfg = new HikariConfig(props)

  private[jdbc] val ds = new HikariDataSource(hcfg)

  lazy val provider = new DataSourceConnectionProvider(ds)

  def close(): Unit = ds.close()

  def isClosed: Boolean = ds.isClosed
}