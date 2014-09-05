package uk.co.sprily
package mqtt

sealed trait QoS { val value: Int }
final case object AtMostOnce extends QoS { val value = 0 }
final case object AtLeastOnce extends QoS { val value = 1 }
final case object ExactlyOnce extends QoS { val value = 2 }

object QoS {
  def apply(i: Int): Option[QoS] = i match {
    case 0 => Some(AtMostOnce)
    case 1 => Some(AtLeastOnce)
    case 2 => Some(ExactlyOnce)
    case _ => None
  }
}
