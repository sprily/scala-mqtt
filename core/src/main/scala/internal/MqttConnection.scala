package uk.co.sprily
package mqtt
package internal

import scala.language.higherKinds
import scala.concurrent.duration._

import scalaz._
import scalaz.syntax.monad._

/**
  * Abstracts over the underlying MQTT library, eg. paho.  The interface
  * is modelled on the paho MQTT client as that's the intended implementor.
  *
  * A mutable object by necessity since it handles the connection to the
  * broker.  This is reflected in the type parameter `M`, which is
  * interpretted to mean a monad capable of modelling failure.  The
  * intended purpose of this is to describe both synchronous and asynchronous
  * implementations (by `Try` and `Future` respectively).
  *
  * Connecting to a broker returns a `MqttConnection` which maintains the
  * underlying connection through unexpected disconnections.  ie - it should
  * attempt periodic re-connections until either a re-connection is successful
  * or `disconnect()` is called.
  *
  * Any unexpected disconnections are *not* transparent to the user, that
  * is, the user may call a method such as `subscribe()` on the connection,
  * and that call may fail because the underlying connection is broken.  This
  * design decision gives the user the oppurtunity to decide what to in
  * these situations.
` *
  * The returned `MqttConnection` should be thread-safe.
  *
  */
protected[mqtt] trait MqttConnectionModule[M[+_]] { self =>

  implicit def M: Monad[M]

  type MqttConnection
  type HandlerToken <: HandlerTokenLike
  type MessageHandler = (Topic, MqttMessage) => Unit
  type ConnectionHandler = ConnectionStatus => Unit

  class InactiveConnectionException extends IllegalStateException("Active connection required")
  class ActiveConnectionException extends IllegalStateException("In-active connection required")

  trait HandlerTokenLike {
    def cancel(): Unit
  }

  /** 
    * Connect to the given broker, and maintain the connection
    * through any unexpected disconnections.
    *
    * Upon successful initial connection, the return `MqttConnection` will
    * attempt to maintain theunderlying connection.  *However*, if the
    * *initial* connection fails, no automatic re-attempts are made.
    *
    * The callback is attached to the connection prior to the connection
    * being initialised to avoid the race-condition.
    */
  def connectWithHandler(options: MqttOptions,
                         callbacks: Seq[ConnectionHandler]): M[(MqttConnection, Seq[HandlerToken])]

  /**
    * Connect to the given broker, and maintain the connection
    * through any unexpected disconnections.
    *
    * Upon successful initial connection, the return `MqttConnection` will
    * attempt to maintain theunderlying connection.  *However*, if the
    * *initial* connection fails, no automatic re-attempts are made.
    */
  def connect(options: MqttOptions): M[MqttConnection] = {
    connectWithHandler(options, Nil).map(_._1)
  }

  /**
    * Disconnect from the broker, and clean up resources.
    *
    * Disconnection may not be immediate as there may be in-flight (QoS > 0)
    * messages.  However, once the disconnection is initiated no new messages
    * should be processed.
    *
    * @throw InactiveConnection if `disconnect()` has already been called on
    * the given `MqttConnection`.
    */
  def disconnect(conn: MqttConnection, quiesce: FiniteDuration = 30.seconds): M[Unit]

  /**
    * Publish a given payload to the given topic.
    *
    * Successful completion of the publication occurs when the full message
    * flow for the given QoS level has occurred.  In the case where the
    * underlying connection is broken during the time the message is
    * in-flight, messages will be delivered once the connection is
    * re-established providing that:
    *
    *  - the connection was made with `cleanSession` set to `false`.
    *  - depending upon when the failure occurs, QoS=0 messages may
    *    not be delivered.
    *
    * If the underlying connection happens to be broken at the point of
    * publication (ie - before the message is in-flight), then the publication
    * fails immediately.  It is up to the caller to decide whether to re-try
    * or not.
    *
    * This is modelled after the behaviour exhibited by the paho MQTT client,
    * in particular, the following behaviour is observed on the paho client:
    *
    *  - when the underlying connection is broken, failure is immediate.
    *  - when the underlying connection is broken when the message is
    *    in-flight, the behaviour depends upon 2 things:
    *
    *     - the QoS level of the message
    *     - the cleanSession flag
    *
    *    The "task" of publication may either complete or not (where
    *    completion may be success *or* failure); and the message may
    *    or may not be received by the broker in the case of task completion.
    *    In the case of task in-completion, the msg is not delivered.
    *
    *                               Session
    *       |       clean             |        unclean          |
    *       +---------------------------------------------------
    *    Q 0| completes; msg not rcvd | completes; msg not rcvd |
    *      -----------------------------------------------------
    *    o 1| does not complete       | completes; msg rcvd     |
    *      -----------------------------------------------------
    *    S 2| does not complete       | completes; msg rcvd     |
    *      -----------------------------------------------------
    *
    *    Note - in cases where the "task" doesn't complete, this means in the
    *           case of a syncrhonous interface, the task will block forever;
    *           and in the case of an asynchronous interface, the `Future`
    *           will never be completed.
    *
    *           The consequence of this that timeouts should be considered
    *           by implementors.
    *
    */
  def publish(conn: MqttConnection,
              topic: Topic,
              payload: Seq[Byte],
              qos: QoS,
              retained: Boolean): M[Unit]

  /**
    * Subscribe to the given topics.
    *
    * Successful completion of the subscription occurs when the full `SUB`
    * message flow has been completed.
    *
    * When the underlying connection is broken, then subscription fails
    * immediately.
    *
    * When the underlying connection breaks whilst the `SUB` message is
    * in-flight, the "task" of subscription may never complete.  In fact,
    * the behaviour of the paho client is to *never complete* even when the
    * connection is re-established.  It's because of this that implementors
    * should consider timing-out on a blocking interface; and users should
    * consider timing out on an asynchronous interface.
    *
    * It's safe to subscribe to the same topic-pattern more than once: the
    * broker will only send a single message even if multiple patterns match.
    *
    * When the cleanSession flag is set to `false`, the broker will
    * take care of re-instantiating existing subscriptions for the given
    * client.
    *
    * When the cleanSession flag is set to `true`, the implementor should
    * not re-subscribe across unexpected connections.  Leave that choice to
    * the user.
    *
    */
  def subscribe(conn: MqttConnection, topics: Seq[TopicPattern], qos: QoS): M[Unit]

  /**
    * Unsubscribe from the given topics.
    *
    * Successful completion of the subscription occurs when the full `UNSUB`
    * message flow has been completed.
    *
    * When the underlying connection is broken, then un-subscription fails
    * immediately.
    *
    * When the underlying connection breaks whilst the `UNSUB` message is
    * in-flight, the "task" of un-subscription may never complete.  In fact,
    * the behaviour of the paho client is to *never complete* even when the
    * connection is re-established.  It's because of this that implementors
    * should consider timing-out on a blocking interface; and users should
    * consider timing out on an asynchronous interface.
    *
    */
  def unsubscribe(conn: MqttConnection, topics: Seq[Topic]): M[Unit]

  /**
    * Attach a callback to be notified of all incoming messages.
    */
  def attachMessageHandler(conn: MqttConnection, callback: MessageHandler): HandlerToken

  /**
    * Attach a callback to be notified of changes to the connection status
    * of the underlying connection.
    */
  def attachConnectionHandler(conn: MqttConnection, callback: ConnectionHandler): HandlerToken

  /**
   * Infix operators delegate to module functions.
   */
  //implicit class MqttConnectionOps(conn: MqttConnection) {
  //  def disconnect() = self.disconnect(conn)
  //  def publish(topic: Topic, payload: Seq[Byte], qos: QoS, retained: Boolean) = {
  //    self.publish(conn, topic, payload, qos, retained)
  //  }
  //  def subscribe(topics: Seq[TopicPattern], qos: QoS) = self.subscribe(conn, topics, qos)
  //  def unsubscribe(topics: Seq[Topic]) = self.unsubscribe(conn, topics)
  //  def attachMessageHandler(callback: MessageHandler) = self.attachMessageHandler(conn, callback)
  //  def attachConnectionHandler(callback: ConnectionHandler) = self.attachConnectionHandler(conn, callback)
  //}
}
