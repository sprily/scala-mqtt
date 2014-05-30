package uk.co.sprily
package mqtt
package connection

import scala.language.higherKinds

import scala.concurrent.Future

/**
  * Abstracts over the underlying MQTT library, eg. paho or fusesource etc.
  */
protected[mqtt] trait MqttConnection[M[+_]] {
  def open(options: MqttOptions): M[Unit]
  def close(): M[Unit]
}
