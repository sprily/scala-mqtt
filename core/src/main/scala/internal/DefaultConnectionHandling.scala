package uk.co.sprily
package mqtt
package internal

import scala.language.higherKinds

import java.util.concurrent.atomic.AtomicReference

protected[internal] trait DefaultConnectionHandling[M[+_]] { this: MqttConnectionModule[M] =>

  trait ConnectionHandling {

    private val handlersRef = new AtomicReference[List[ConnectionHandler]](Nil)

    protected[internal] def registerConnectionHandler(h: ConnectionHandler): HandlerToken = {
      handlersRef.update { handlers => h :: handlers }
      HandlerToken( () =>
        handlersRef.update { handlers => handlers.filter(_ != h) }
      )
    }

    protected[this] def notifyConnectionHandlers(status: ConnectionStatus): Unit = {
      handlersRef.get.foreach { h => h(status) }
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
