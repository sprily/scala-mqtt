package uk.co.sprily
package mqtt
package internal
package util

import java.util.concurrent.atomic.AtomicReference

trait AtomicImplicits {

  implicit class AtomicOps[T](val ref: AtomicReference[T]) {

    @annotation.tailrec
    final def update(f: T => T): (T,T) = {
      val oldT = ref.get
      val newT = f(oldT)
      ref.compareAndSet(oldT, newT) match {
        case true  => (oldT, newT)
        case false => update(f)
      }
    }

  }
}
