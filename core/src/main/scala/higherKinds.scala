package uk.co.sprily
package mqtt

import scala.language.higherKinds

object HigherKindImplicits {

  type Id[+T] = T

  implicit val Pure = new Pure[Id] {
    def pure[A](a: A) = a
  }
}

trait Pure[T[+_]] {
  def pure[A](a: A): T[A]
}
