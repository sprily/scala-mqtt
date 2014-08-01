package uk.co.sprily
package mqtt
package internal

import scala.language.higherKinds

import scalaz._
import scalaz.syntax.monad._

protected[mqtt] trait PersistentMqttConnectionModule[M[+_]]
              extends MqttConnectionModule[M] {

  val mqttModule: MqttConnectionModule[M]

  override def connect(options: MqttOptions, callbacks: Seq[ConnectionHandler]=Nil) = {
    val mqttConnection = MqttConnection(options, callbacks)
    //mqttModule.connect(options, List(handler)).map(_ => mqttConnection)
    mqttConnection.connect()
  }

  class MqttConnection(
          options: MqttOptions,
      var wrapped: Option[mqttModule.MqttConnection],
          callbacks: List[ConnectionHandler]) {

    def connect(): M[this.type] = {
      val handler: ConnectionHandler = {
        case ConnectionStatus(true)  => { }
        case ConnectionStatus(false) => { }
      }
      mqttModule.connect(options, handler :: callbacks) >> M.pure(this)
    }

  }

  object MqttConnection {
    def apply(options: MqttOptions, callbacks: Seq[ConnectionHandler]) = {
      new MqttConnection(options, None, callbacks.toList)
    }
  }

  /** Boilerplate **/
  override def disconnect(conn: MqttConnection) = mqttModule.disconnect(conn.wrapped.get)
  override def publish(conn: MqttConnection,
                       topic: Topic,
                       payload: Seq[Byte],
                       qos: QoS,
                       retained: Boolean) = mqttModule.publish(conn.wrapped.get, topic, payload, qos, retained)

  override def subscribe(conn: MqttConnection, topics: Seq[TopicPattern], qos: QoS) = {
    mqttModule.subscribe(conn.wrapped.get, topics, qos)
  }

  override def unsubscribe(conn: MqttConnection, topics: Seq[Topic]) = {
    mqttModule.unsubscribe(conn.wrapped.get, topics)
  }

  override def attachMessageHandler(conn: MqttConnection, callback: MessageHandler) = {
    mqttModule.attachMessageHandler(conn.wrapped.get, callback)
  }

  override def attachConnectionHandler(conn: MqttConnection, callback: ConnectionHandler) = {
    mqttModule.attachConnectionHandler(conn.wrapped.get, callback)
  }

  implicit class HandlerToken(val wrapped: mqttModule.HandlerToken) extends HandlerTokenLike {
    def cancel() = wrapped.cancel()
  }
}
