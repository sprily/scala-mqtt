package uk.co.sprily
package mqtt

import org.scalacheck._
import org.scalacheck.Gen._
import org.scalacheck.Prop.forAll
import org.scalacheck.Arbitrary.arbitrary

import scalaz.scalacheck.ScalazProperties._

object QoSChecks extends Properties("QoS") {
  implicit val arbitraryQoS: Arbitrary[QoS] = Arbitrary(Gen.oneOf(AtMostOnce, AtLeastOnce, ExactlyOnce))

  property("is a monoid") = monoid.laws[QoS]
}

//import org.scalacheck.Gen._
//import org.scalacheck.Prop.forAll
//import org.scalacheck.Arbitrary.arbitrary

