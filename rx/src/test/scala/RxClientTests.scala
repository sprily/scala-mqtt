package uk.co.sprily
package mqtt
package rx

import scala.language.higherKinds

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import org.scalatest._
import org.scalatest.Matchers

import org.scalamock._
import org.scalamock.scalatest.MockFactory

import _root_.rx.lang.scala.Observable

import mqtt.connection.MqttConnection
import mqtt.HigherKindImplicits._

class RxClientTests extends FlatSpec with Matchers
                                     with MockFactory {

  "A mqtt Connection" should "only open connection if active" in {
    val options = MqttOptions("localhost", 1234)
    val connection = stub[MqttConnection[Id]]

    val client = new RxClient[Id](connection, options)
    client.connect()
    client.connect()

    (connection.open _).verify(options).once
  }

  private def successfully = {}
}
