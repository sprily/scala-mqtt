package uk.co.sprily
package mqtt
package internal

trait CancellationToken {
  def cancel(): Unit
}

object CancellationToken {
  def apply(f: => Unit): CancellationToken = new CancellationToken {
    def cancel() = f
  }
}
