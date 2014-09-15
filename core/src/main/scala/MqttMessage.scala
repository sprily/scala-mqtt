package uk.co.sprily
package mqtt

case class MqttMessage(
    topic: Topic,
    payload: Seq[Byte],
    qos: QoS = AtMostOnce,
    retained: Boolean = false,
    dup: Boolean = false)
