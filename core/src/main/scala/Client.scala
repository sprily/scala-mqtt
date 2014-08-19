package uk.co.sprily
package mqtt

import scala.language.higherKinds

/** A connection to an MQTT broker for subscribtion and publication of data.
  *
  * Data receieved from the broker, as well as status of the underlying TCP
  * socket, is published over an abstracted type, ``M``.  ``M`` must provide a
  * way for the client to receieve data (obviously!), but it must also provide
  * a way for the client to close the stream.  An example of a sufficient type
  * is an ``Observable`` from the reactive extensions library.
  *
  * ==Behaviour==
  * Once a connection to the broker is established, the ``Client`` will
  * attempt to re-connect after any un-wanted disconnections.  This should
  * happen in the background, and not affect any callers of any of the other
  * methods.  Behaviour of the ``Client`` when a client attempts to publish
  * a message when the underlying TCP connection is broken is undefined by this
  * interface, it is left to the implementor.
  *
  * The only time re-connection is not attemped is when connection fails on the
  * initial call to [[uk.co.sprily.mqtt.Client.open()]].
  *
  */
trait Client[M[+_], N[+_]] {

  /** Open the connection.
    *
    * This may fail if an initial connection cannot be made.
    */
  def connect(): N[Unit]

  /** Close the connection.
    *
    * Once called, no automatic re-connects are made.  But the connection can
    * be re-opened again by calling
    * [[uk.co.sprily.mqtt.Client.open]].
    */
  def disconnect(): N[Unit]

  /** A stream of the changing connection status, ie online or offline.
    *
    * @return the stream of [[uk.co.sprily.mqtt.MqttClientStatus]]
    *
    * The returned stream should always contain the '''current''' connection
    * status as an initial value.  Therefore a client will always receive at
    * least one element from the stream, and it will include the connection
    * status '''at the time of subscription'''.
    */
  def status: M[ConnectionStatus]

  /** The stream of '''all''' data messages receieved from the broker by
    * '''already established''' subscriptions.
    *
    * @return the stream of [[uk.co.sprily.mqtt.MqttMessage]]
    *
    * ''Note:'' Calling this will '''not''' subscribe to any new topics on the
    * broker.  The purpose of this method is to be able to inspect data
    * messages that are being receieved as a result of other subscriptions.  Ie
    * - it's useful for debugging/logging/tracing etc.
    */
  def data(): M[MqttMessage]

  /** The stream of data messages receieved from the broker, filtered by the
    * given topic.
    *
    * @param topic TopicPattern is the subscription pattern. It may include
    *              wildcards.
    * @return the stream of [[uk.co.sprily.mqtt.MqttMessage]]
    *
    * Calling this method will, if necessary, cause this connection to
    * subscribe to the given topic on the broker.
    *
    * The `Client` will not necessarily send a new `SUBSCRIPTION` message
    * to the broker if it knows that it is already subscribed to the same topic
    * pattern.
    *
    * When the resulting ``M`` is eventually unsubscribed from by a downstream
    * consumer, this should trigger an `UNSUBSCRIBE` message to the broker if
    * and only if there are no ''other'' subscribers to the given topic(s) over
    * this connection.
    */
  def data(topic: TopicPattern): M[MqttMessage] = data(List(topic))

  /** The stream of data messages received from the broker, filtered by the
    * given topics.
    *
    * @param topics Seq[TopicPattern] the subscription patterns to filter through.
    * @return the stream of [[uk.co.sprily.mqtt.MqttMessage]]
    *
    * Calling this method will, if necessary, cause this connection to
    * subscribe to the given topics on the broker.
    *
    * The `Client` will only send a `SUBSCRIPTION` message to the broker if
    * any of the topic patterns are not currently being subscribed to.
    *
    * When the resulting ``rx.lang.scala.Observable`` is eventually
    * unsubscribed from by a downstream consumer, this should trigger an
    * `UNSUBSCRIBE` message to the broker if and only if there are no ''other''
    * subscribers to the given topic(s) over this connection.
    */
  def data(topic: TopicPattern, topics: TopicPattern*): M[MqttMessage] = {
    data(topic :: topics.toList)
  }

  protected def data(topics: Seq[TopicPattern]): M[MqttMessage]

  /** Asynchronously publish some data to the given topic.
    */
  def publish(topic: Topic,
              payload: Array[Byte],
              qos: QoS = AtLeastOnce,
              retain: Boolean = false): N[Unit]

}

