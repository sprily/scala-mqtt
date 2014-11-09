package uk.co.sprily
package mqtt

import scala.language.higherKinds
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.typesafe.scalalogging.slf4j.StrictLogging

import scalaz._
import scalaz.std.list._
import scalaz.std.map._
import scalaz.syntax.monad._
import scalaz.syntax.foldable._
import scalaz.syntax.monoid._

import util._

object Main {
  import scala.concurrent.Await
  import scala.concurrent.duration._
  val client = Await.result(AsyncContClient.connect(MqttOptions.cleanSession()), 3.seconds)
  val cont = AsyncContClient.data(client)

  cont { msg => println(msg) }
}

object AsyncContClient extends ContClient {
  val ec = scala.concurrent.ExecutionContext.Implicits.global
  protected lazy val connectionModule = mqtt.internal.PahoMqttConnection
}

trait ContClient extends ClientModule[Cont]
                    with StrictLogging {

  protected implicit val ec: ExecutionContext
  protected val connectionModule: mqtt.internal.MqttConnectionModule

  override def connect(options: MqttOptions) = {
    connectionModule.connect(options).map(new Client(_))
  }

  override def disconnect(client: Client) = connectionModule.disconnect(client.connection)

  override def status(client: Client): Cont[ConnectionStatus] = {
    ???
    //client.status
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

  override def publish(client: Client, topic: Topic, payload: Array[Byte], qos: QoS, retain: Boolean = false) = ???

  case class Client(private[mqtt] val connection: connectionModule.MqttConnection)

}
