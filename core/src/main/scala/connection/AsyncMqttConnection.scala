package uk.co.sprily
package mqtt
package connection

import scala.concurrent.Future

protected[mqtt] trait AsyncMqttConnection {
  def open(options: MqttOptions): Future[Unit]
  def close(): Future[Unit]
}
