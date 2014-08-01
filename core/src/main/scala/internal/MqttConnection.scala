package uk.co.sprily
package mqtt
package internal

import scala.language.higherKinds

import scalaz._
import scalaz.syntax.monad._

/**
  * Abstracts over the underlying MQTT library, eg. paho
  *
  * A mutable object by necessity since it handles the connection to the
  * broker.  This is reflected in the type parameter `M`, which is
  * interpretted to mean a monad capable of modelling failure.
  */
protected[mqtt] trait MqttConnectionModule[M[+_]] { self =>

  implicit def M: Monad[M]

  type MqttConnection
  type HandlerToken <: HandlerTokenLike
  type MessageHandler = (Topic, MqttMessage) => Unit
  type ConnectionHandler = ConnectionStatus => Unit

  def connect(options: MqttOptions, connHandlers: Seq[ConnectionHandler]=Nil): M[MqttConnection]
  def disconnect(conn: MqttConnection): M[Unit]
  def publish(conn: MqttConnection,
              topic: Topic,
              payload: Seq[Byte],
              qos: QoS,
              retained: Boolean): M[Unit]
  def subscribe(conn: MqttConnection, topics: Seq[TopicPattern], qos: QoS): M[Unit]
  def unsubscribe(conn: MqttConnection, topics: Seq[Topic]): M[Unit]
  def attachMessageHandler(conn: MqttConnection, callback: MessageHandler): HandlerToken
  def attachConnectionHandler(conn: MqttConnection, callback: ConnectionHandler): HandlerToken


  trait HandlerTokenLike {
    def cancel(): Unit
  }

  /**
   * Infix operators delegate to module functions.
   */
  implicit class MqttConnectionOps(conn: MqttConnection) {
    def disconnect() = self.disconnect(conn)
    def publish(topic: Topic, payload: Seq[Byte], qos: QoS, retained: Boolean) = {
      self.publish(conn, topic, payload, qos, retained)
    }
    def subscribe(topics: Seq[TopicPattern], qos: QoS) = self.subscribe(conn, topics, qos)
    def unsubscribe(topics: Seq[Topic]) = self.unsubscribe(conn, topics)
    def attachMessageHandler(callback: MessageHandler) = self.attachMessageHandler(conn, callback)
    def attachConnectionHandler(callback: ConnectionHandler) = self.attachConnectionHandler(conn, callback)
  }
}
