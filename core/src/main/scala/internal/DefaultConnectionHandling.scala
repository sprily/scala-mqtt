package uk.co.sprily
package mqtt
package internal

import scala.language.higherKinds

import java.util.concurrent.atomic.AtomicReference

import util.AtomicOps

protected[internal] trait DefaultConnectionHandling { this: MqttConnectionModule =>


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

}
