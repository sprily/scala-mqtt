package uk.co.sprily
package mqtt

import scalaz._
import scalaz.syntax.monoid._

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

  implicit val QosMonoid: Monoid[QoS] = new Monoid[QoS] {
    def zero = AtMostOnce
    def append(q1: QoS, q2: => QoS) = QoS(Math.max(q1.value, q2.value)).get
  }

  implicit val QoSEqual: Equal[QoS] = new Equal[QoS] {
    def equal(q1: QoS, q2: QoS): Boolean = q1 == q2
  }

}
