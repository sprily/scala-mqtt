package uk.co.sprily
package mqtt
package internal

import scala.concurrent.{Future, promise}

import akka.actor.{Actor, Props, ActorRef}
import akka.pattern.ask

import scalaz._
import scalaz.contrib.std.scalaFuture._

import org.eclipse.paho.client.{mqttv3 => paho}

protected[mqtt] object PahoMqttConnectionModule extends MqttConnectionModule[Future] {

  override implicit def M = implicitly[Monad[Future]]

  def connect(options: MqttOptions, connHandlers: Seq[ConnectionHandler]=Nil) = {
    val conn = MqttConnection_(options)
    ask(conn, Connect).mapTo[MqttConnection]
    val p = promise[MqttConnection]
    val listener = new paho.IMqttActionListener {
      def onFailure(token: paho.IMqttToken, t: Throwable) = p.tryFailure(t)
      def onSuccess(token: paho.IMqttToken) = p.trySuccess(conn)
    }
    val token = conn.client.connect(new paho.MqttConnectOptions(),
                                    null,
                                    listener)
    p.future
  }

  def disconnect(conn: MqttConnection) = {
    val p = promise[Unit]
    val listener = new paho.IMqttActionListener {
      def onFailure(token: paho.IMqttToken, t: Throwable) = p.failure(t)
      def onSuccess(token: paho.IMqttToken) = p.success(())
    }
    conn.client.disconnect(10000, null, listener)
    p.future
  }

  def publish(conn: MqttConnection,
              topic: Topic,
              payload: Seq[Byte],
              qos: QoS,
              retained: Boolean) = ???
  def subscribe(conn: MqttConnection, topics: Seq[TopicPattern], qos: QoS) = ???
  def unsubscribe(conn: MqttConnection, topics: Seq[Topic]) = ???
  def attachMessageHandler(conn: MqttConnection, callback: MessageHandler) = ???
  def attachConnectionHandler(conn: MqttConnection, callback: ConnectionHandler) = ???

  class MqttConnection_(val client: paho.IMqttAsyncClient) extends Actor
                                                             with paho.MqttCallback {

    client.setCallback(this)

    def receive = {
      /** Commands issued **/
      case Connect    => { }
      case Disconnect => { }

      /** Paho MQTT callback messages **/
      case ConnectionLost(reason)     => { }
      case DeliveryComplete(token)    => { }
      case MessageArrived(topic, msg) => { }
    }

    /** Paho MQTT callback interface **/
    override def connectionLost(t: Throwable) = self ! ConnectionLost(t)
    override def deliveryComplete(token: paho.IMqttDeliveryToken) = self ! DeliveryComplete(token)
    override def messageArrived(topic: String, msg: paho.MqttMessage) = self ! MessageArrived(topic, msg)

  }

  type MqttConnection = ActorRef

  object MqttConnection_ {
    def apply(options: MqttOptions) = {
      Props(new MqttConnection_(
        client = new paho.MqttAsyncClient(options.host, paho.MqttAsyncClient.generateClientId()))
      )
    }
  }

  private case object Connect
  private case object Disconnect

  private case class ConnectionLost(reason: Throwable)
  private case class DeliveryComplete(token: paho.IMqttDeliveryToken)
  private case class MessageArrived(topic: String, msg: paho.MqttMessage)

}
