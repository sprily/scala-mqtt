package uk.co.sprily
package mqtt
package rx

import scala.language.higherKinds

import scala.concurrent.Future

import java.util.concurrent.atomic.AtomicBoolean

import _root_.rx.lang.scala.{Observable, Subject}

import com.typesafe.scalalogging.slf4j.Logging

import mqtt.connection.MqttConnection

class RxClient[N[+_] : Pure](
    private val connection: MqttConnection[N],
    private val options: MqttOptions) extends Client[Observable, N]
                                         with Logging {

  val N = implicitly[Pure[N]]

  /** True iff actively trying to connect to the broker.  It may not be 
    * _connected_ (eg. there may be network issues), but it will be attempting
    * to (re-)connect.
    * An inactive client is definitely not connected to the broker, and is
    * making no attempts to re-connect.
    */
  private val active = new AtomicBoolean(false)

  override def connect(): N[Unit] = {
    logger.info("Client received request to connect to MQTT broker.")
    val wasInactive = active.compareAndSet(false, true)
    wasInactive match {
      case true =>
        logger.info("Client was inactive, attempting to open connection to broker.")
        connection.open(options)
      case false =>
        logger.info("Client was already active.")
        N.pure({})
    }
  }

  override def disconnect(): N[Unit] = {
    logger.info("Client received request to disconnect from MQTT broker.")
    val wasActive = active.compareAndSet(true, false)
    wasActive match {
      case true =>
        logger.info("Client was active, closing any connections.")
        connection.close()
      case false =>
        logger.info("Client was already inactive.")
        N.pure({})
    }
  }

  override def data(): Observable[MqttMessage] = ???
  override def publish(topic: Topic, payload: Array[Byte], qos: QoS, retain: Boolean): Future[Unit] = ???
  override def status: Observable[ConnectionStatus] = ???
  override protected def data(topics: Seq[TopicPattern]): Observable[MqttMessage] = ???

}
