package uk.co.sprily
package mqtt

case class MqttMessage(
    payload: Seq[Byte],
    qos: QoS = AtMostOnce,
    retained: Boolean = false,
    dup: Boolean = false)
