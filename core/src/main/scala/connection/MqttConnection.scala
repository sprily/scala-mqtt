package uk.co.sprily
package mqtt
package connection

import scala.language.higherKinds

import scala.concurrent.Future

protected[mqtt] trait MqttConnection[M[+_]] {
  def open(options: MqttOptions): M[Unit]
  def close(): M[Unit]
}
