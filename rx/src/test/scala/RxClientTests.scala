package uk.co.sprily
package mqtt
package rx

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import org.scalatest._
import org.scalatest.Matchers

import org.scalamock._
import org.scalamock.scalatest.MockFactory

import _root_.rx.lang.scala.Observable

import mqtt.connection.AsyncMqttConnection

class RxClientTests extends FlatSpec with Matchers
                                     with MockFactory {

  "A mqtt Connection" should "only open connection if active" in {
    val options = MqttOptions("localhost", 1234)
    val connection = stub[AsyncMqttConnection]

    val client = new RxClient(connection, options)
    (connection.open _) when(*) returns successfully

    client.connect()

    (connection.open _).verify(options).once
  }

  private def successfully = Future.successful({})
}
