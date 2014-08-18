package uk.co.sprily
package mqtt

case class MqttOptions(
    host: String,
    port: Int,
    clientId: ClientId,
    cleanSession: Boolean)

object MqttOptions {

  def cleanSession(host: String ="127.0.0.1", port: Int = 1883) = {
    new MqttOptions(host, port, ClientId.random(), true)
  }

}
