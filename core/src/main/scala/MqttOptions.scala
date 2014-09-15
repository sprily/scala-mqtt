package uk.co.sprily
package mqtt

import scala.concurrent.duration._

case class MqttOptions(
    host: String,
    port: Int,
    clientId: ClientId,
    cleanSession: Boolean,
    username: Option[String],
    password: Option[String],
    keepAliveInterval: FiniteDuration)

object MqttOptions {

  def cleanSession(host: String ="127.0.0.1",
                   port: Int = 1883,
                   username: Option[String] = None,
                   password: Option[String] = None,
                   keepAliveInterval: FiniteDuration = 60.seconds) = {
    new MqttOptions(host = host,
                    port = port,
                    clientId = ClientId.random(),
                    cleanSession = true,
                    username = username,
                    password = password,
                    keepAliveInterval = keepAliveInterval)
  }

}
