package uk.co.sprily
package mqtt
package internal

import scala.language.higherKinds

import java.util.concurrent.atomic.AtomicReference

protected[internal] trait DefaultConnectionHandling[M[+_]] { this: MqttConnectionModule[M] =>


  class NotificationHandler[T] {

    type Callback = T => Unit
    private[this] val subscribersRef = new AtomicReference[List[Callback]](Nil)

    def register(c: Callback): HandlerToken = {
      subscribersRef.update { subscribers => c :: subscribers }
      HandlerToken( () =>
        subscribersRef.update { subscribers => subscribers.filter(_ != c) }
      )
    }

    def notify(t: T): Unit = {
      subscribersRef.get.foreach { cb => cb(t) }
    }
  }

  case class HandlerToken(f: () => Unit) extends HandlerTokenLike { def cancel() = f() }

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
