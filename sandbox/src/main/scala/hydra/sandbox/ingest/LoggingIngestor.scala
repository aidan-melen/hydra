package hydra.sandbox.ingest

import hydra.core.ingest.{HydraRequest, Ingestor}
import hydra.core.protocol._
import hydra.core.transport.{RecordFactory, StringRecord}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * A simple example transport that writes requests with a certain attribute to a log.
  *
  * Created by alexsilva on 2/27/17.
  */
// $COVERAGE-OFF$
class LoggingIngestor extends Ingestor {
  override def initTimeout = 2.seconds

  override val recordFactory = new RecordFactory[String, String] {

    override def build(request: HydraRequest)(implicit ec: ExecutionContext) =
      Future.successful(
        StringRecord("", "", request.payload, request.ackStrategy)
      )
  }
  ingest {
    case Publish(request) =>
      sender ! (if (request.metadataValueEquals("logging-enabled", "true")) Join
                else Ignore)

    case Ingest(record, _) =>
      log.info(record.payload.toString)
      sender ! IngestorCompleted
  }
}

// $COVERAGE-ON
