//package uk.co.sprily
//package mqtt
//package rx
//
//import scala.language.higherKinds
//
//import scala.concurrent.{Await, Future, future}
//import scala.concurrent.duration._
//import scala.concurrent.ExecutionContext.Implicits.global
//import scala.util.Try
//
//import org.scalatest._
//import org.scalatest.Matchers
//
//import org.scalamock._
//import org.scalamock.scalatest.MockFactory
//
//import _root_.rx.lang.scala.Observable
//
//import scalaz._
//import scalaz.Id._
//import scalaz.contrib.std.utilTry._
//import scalaz.contrib.std.scalaFuture._
//
//import mqtt.connection.MqttConnection
//
//class RxClientTests extends FlatSpec with Matchers
//                                     with MockFactory {
//
//  "A mqtt Connection" should "only open connection if inactive" in {
//    val options = MqttOptions("localhost", 1234)
//    val connection = stub[MqttConnection[Id]]
//
//    val client = new RxClient[Id](connection, options)
//    client.connect()
//    client.connect()
//
//    (connection.open _).verify(options).once
//  }
//
//  "A mqtt Connection" should "only close connection if active" in {
//    val options = MqttOptions("localhost", 1234)
//    val connection = stub[MqttConnection[Id]]
//
//    val client = new RxClient[Id](connection, options)
//    client.connect()
//
//    client.disconnect()
//    client.disconnect()
//
//    (connection.open _).verify(options).once
//    (connection.close _).verify.once
//  }
//
//  "A mqtt Connection" should "publish its connection status" in {
//    val options = MqttOptions("localhost", 1234)
//    val connection = stub[MqttConnection[Id]]
//    val client = new RxClient[Id](connection, options)
//
//    // Check status before connection takes place
//    val initialStatus = client.status.take(1).toBlockingObservable.single
//    initialStatus should equal (ConnectionStatus(false))
//
//    // Check status after connection
//    client.connect()
//    val statusAfterConnection = client.status.take(1).toBlockingObservable.single
//    statusAfterConnection should equal (ConnectionStatus(true))
//
//    // Check status after client-invoked disconnection
//    client.disconnect()
//    val statusAfterDisconnection = client.status.take(1).toBlockingObservable.single
//    statusAfterDisconnection should equal (ConnectionStatus(false))
//  }
//
//  "A mqtt Connection" should "check if connection attempt fails" in {
//    val options = MqttOptions("localhost", 1234)
//    val connection = stub[MqttConnection[Try]]
//    val client = new RxClient[Try](connection, options)
//
//    // Set the connection up to fail at connection time
//    (connection.open _).when(*).returns(Try { throw new java.io.IOException("oh dear") })
//
//    // Attempt to connect
//    val result = client.connect()
//    result.isSuccess should equal (false)
//
//    val statusAfterConnection = client.status.take(1).toBlockingObservable.single
//    statusAfterConnection should equal (ConnectionStatus(false))
//  }
//
//  "A mqtt Connection" should "check if connection attempt fails (Future)" in {
//    val options = MqttOptions("localhost", 1234)
//    val connection = stub[MqttConnection[Future]]
//    val client = new RxClient[Future](connection, options)
//
//    // Set the connection up to fail at connection time
//    (connection.open _).when(*).returns(future { throw new java.io.IOException("oh dear") })
//
//    // Attempt to connect
//    client.connect()
//    val statusAfterConnection = client.status.take(1).toBlockingObservable.single
//    statusAfterConnection should equal (ConnectionStatus(false))
//  }
//
//  "A mqtt connection" should "check if disconnection fails and re-raise the error" in {
//    val options = MqttOptions("localhost", 1234)
//    val connection = stub[MqttConnection[Try]]
//    val client = new RxClient[Try](connection, options)
//
//    // Set the connection up to fail at connection time
//    (connection.open _).when(*).returns(Try {})
//    (connection.close _).when().returns(Try { throw new java.io.IOException("oh dear") })
//
//    client.connect()
//    val result = client.disconnect()
//    result.isSuccess should equal (false)
//
//    val statusAfterConnection = client.status.take(1).toBlockingObservable.single
//    statusAfterConnection should equal (ConnectionStatus(false))
//  }
//
//  private def successfully = {}
//}
