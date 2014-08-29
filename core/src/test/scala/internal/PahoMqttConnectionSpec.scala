package uk.co.sprily
package mqtt
package internal

import scala.concurrent.Await
import scala.concurrent.promise
import scala.concurrent.duration._

import org.eclipse.paho.client.{mqttv3 => paho}

import org.scalatest._

class PahoMqttConnectionSpec extends FlatSpec
                                with Matchers
                                with PahoMqttConnectionModule {

  "A MqttConnection" should "simply connect and disconnect" in {

    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)

    val fConnect = client.initialiseConnection()
    Await.result(fConnect, 5.seconds)

    val fDisconnect = client.disconnect()
    Await.result(fDisconnect, 5.seconds)
  }

  "A MqttConnection" should "propogate initial connection failures" in {
    val fake = new FakePahoAsyncClient with UnsuccessfulConnection with SuccessfulDisconnection
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)

    val fConnect = client.initialiseConnection()
    intercept[java.io.IOException] {
      Await.result(fConnect, 5.seconds)
    }
  }

  "A MqttConnection" should "reconnect if connection is closed" in {
    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)

    // subscribe to receive connection status
    var statuses = List[ConnectionStatus]()
    var countdown = promise[Unit]()
    val connectionStatusChanged = { (status: ConnectionStatus) =>
      statuses = status :: statuses
      if (statuses.length == 3) countdown.success(())
      ()
    }
    client.attachConnectionHandler(connectionStatusChanged)

    // initial connection should succeed
    val fConnect = client.initialiseConnection()
    Await.result(fConnect, 5.seconds)

    // simulate an unexpected disconnection through the paho.MqttCallback interface
    client.connectionLost(new java.io.IOException("Uh oh"))

    // check statuses
    Await.result(countdown.future, 1.seconds)
    statuses should equal (List(true,false,true).map(ConnectionStatus))
  }

  val defaultOptions = MqttOptions.cleanSession()

  trait FakeClientHelpers {
    protected def succeedListener(listener: paho.IMqttActionListener,
                                  token: paho.IMqttToken = new FakeMqttToken()) = {
      listener.onSuccess(token)
      token
    }

    protected def failListener(listener: paho.IMqttActionListener,
                               token: paho.IMqttToken = new FakeMqttToken()) = {
      listener.onFailure(token, new java.io.IOException("something went wrong"))
      token
    }
  }

  trait SuccessfulConnection { this: FakePahoAsyncClient =>
    override def connect(options: paho.MqttConnectOptions,
                         listener: paho.IMqttActionListener) = {
      connected = true
      succeedListener(listener)
    }
  }

  trait UnsuccessfulConnection { this: FakePahoAsyncClient =>
    override def connect(options: paho.MqttConnectOptions,
                         listener: paho.IMqttActionListener) = {
      connected = false
      failListener(listener)
    }
  }

  trait SuccessfulDisconnection { this: FakePahoAsyncClient =>
    override def disconnect(quiesce: Long,
                            listener: paho.IMqttActionListener) = {
      connected = false
      succeedListener(listener)
    }
  }

  /** A base implementation of the paho async client interface **/
  trait FakePahoAsyncClient extends RestrictedPahoInterface with FakeClientHelpers {

    protected var connected = false

    def close(): Unit = { }
    def isConnected(): Boolean = connected
    def connect(options: paho.MqttConnectOptions,
                l: paho.IMqttActionListener): paho.IMqttToken
    def disconnect(quiesce: Long,
                   l: paho.IMqttActionListener): paho.IMqttToken
    def setCallback(cb: paho.MqttCallback): Unit = { }
  }

  class FakeMqttToken extends paho.IMqttToken {
    def getActionCallback(): paho.IMqttActionListener = ???
    def getClient(): paho.IMqttAsyncClient = ???
    def getException(): paho.MqttException = ???
    def getMessageId(): Int = ???
    def getTopics(): Array[String] = ???
    def getUserContext(): Object = ???
    def isComplete(): Boolean = ???
    def setActionCallback(x$1: paho.IMqttActionListener): Unit = ???
    def setUserContext(x$1: Any): Unit = ???
    def waitForCompletion(x$1: Long): Unit = ???
    def waitForCompletion(): Unit = ???
  }

}
