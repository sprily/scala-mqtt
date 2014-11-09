package uk.co.sprily
package mqtt

import java.util.concurrent.atomic.AtomicReference

import scala.language.higherKinds
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.typesafe.scalalogging.slf4j.StrictLogging

import internal.util._

object AsyncSimpleClient extends SimpleClient {
  val ec = scala.concurrent.ExecutionContext.Implicits.global
  protected lazy val connectionModule = mqtt.internal.PahoMqttConnection
}

trait SimpleClient extends ClientModule[Cont]
                     with AtomicImplicits
                     with StrictLogging {

  protected implicit val ec: ExecutionContext
  protected val connectionModule: mqtt.internal.MqttConnectionModule

  override def connect(options: MqttOptions) = {
    val status = new AtomicReference(ConnectionStatus(false))
    val handler = { newStatus: ConnectionStatus =>
      status.update { _ => newStatus }
      ()
    }
    connectionModule.connectWithHandler(options, List(handler)).map {
      case (conn, _) => new Client(conn, status)
    }
  }

  override def disconnect(client: Client) = connectionModule.disconnect(client.connection)

  override def status(client: Client): Cont[ConnectionStatus] = { f =>
    f(client.status.get())
    connectionModule.attachConnectionHandler(client.connection, f)
  }

  override def data(client: Client): Cont[MqttMessage] = { f =>
    connectionModule.attachMessageHandler(client.connection, f)
  }

  override def data(client: Client, topics: Seq[TopicPattern]) = { f =>
    connectionModule.subscribe(client.connection, topics, AtMostOnce)
    connectionModule.attachMessageHandler(client.connection, { msg =>
      if (topics.view.exists(_.matches(msg.topic))) {
        f(msg)
      }
    })
  }

  override def publish(client: Client,
                       topic: Topic,
                       payload: Array[Byte],
                       qos: QoS,
                       retain: Boolean = false) = {
    connectionModule.publish(
      client.connection,
      topic,
      payload.toVector,
      qos,
      retain)
  }

  case class Client(
      private[mqtt] val connection: connectionModule.MqttConnection,
      private[mqtt] val status: AtomicReference[ConnectionStatus]
  )

}
