package uk.co.sprily
package mqtt

import scala.concurrent.duration._

sealed trait ClientPersistence
case object InMemory extends ClientPersistence
case class FilePersistence(dir: String) extends ClientPersistence

case class MqttOptions(
    url: String,
    port: Int,
    clientId: ClientId,
    cleanSession: Boolean,
    username: Option[String],
    password: Option[String],
    keepAliveInterval: FiniteDuration,
    persistence: ClientPersistence)

object MqttOptions {

  def cleanSession(url: String ="tcp://127.0.0.1",
                   port: Int = 1883,
                   username: Option[String] = None,
                   password: Option[String] = None,
                   keepAliveInterval: FiniteDuration = 60.seconds,
                   persistence: ClientPersistence = InMemory) = {
    new MqttOptions(url = url,
                    port = port,
                    clientId = ClientId.random(),
                    cleanSession = true,
                    username = username,
                    password = password,
                    keepAliveInterval = keepAliveInterval,
                    persistence = persistence)
  }

}
