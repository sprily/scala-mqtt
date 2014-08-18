package uk.co.sprily
package mqtt

import scala.List.fill
import scala.util.Random

case class ClientId(s: String) extends AnyVal

object ClientId {

  private val r = new Random()

  def random() = ClientId(s"scala-mqtt-${randomString(10)}")

  private def randomString(length: Int): String = {
    fill(length)(randomChar).mkString("")
  }

  private def randomChar = (r.nextInt('z'-'a' + 1) + 'a').toChar
}
