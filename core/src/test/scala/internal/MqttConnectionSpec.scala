package uk.co.sprily
package mqtt
package internal

import scala.concurrent.Await
import scala.concurrent.duration._

// WARNING: this shadows uk.co.sprily.mqtt.internal.paho
import org.eclipse.paho.client.{mqttv3 => paho}

import org.scalatest._

class ConnectionSpec extends FlatSpec with Matchers
                                      with PahoMqttConnectionModule {

  "A MqttConnection" should "simply connect and disconnect" in {

    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)

    val fConnect = client.initialiseConnection()
    Await.result(fConnect, 5.seconds)

    val fDisconnect = client.closeConnection()
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

  "A MqttConnection" should "reconnect if connection is closed" in (pending)
  "A MqttConnection" should "disconnect successfully when underlying connection is closeed" in (pending)

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
                         context: Any,
                         listener: paho.IMqttActionListener) = {
      connected = true
      succeedListener(listener)
    }
  }

  trait UnsuccessfulConnection { this: FakePahoAsyncClient =>
    override def connect(options: paho.MqttConnectOptions,
                         context: Any,
                         listener: paho.IMqttActionListener) = {
      connected = false
      failListener(listener)
    }
  }

  trait SuccessfulDisconnection { this: FakePahoAsyncClient =>
    override def disconnect(quiesce: Long,
                            context: Any,
                            listener: paho.IMqttActionListener) = {
      connected = false
      succeedListener(listener)
    }
  }

  /** A base implementation of the paho async client interface **/
  trait FakePahoAsyncClient extends paho.IMqttAsyncClient with FakeClientHelpers {

    protected var connected = false

    def close(): Unit = { }
    def isConnected(): Boolean = connected
    def connect(options: paho.MqttConnectOptions,
                context: Any,
                l: paho.IMqttActionListener): paho.IMqttToken
    def disconnect(quiesce: Long,
                   context: Any,
                   l: paho.IMqttActionListener): paho.IMqttToken

    def connect(context: Any, l: paho.IMqttActionListener) = connect(null, context, l)
    def connect(options: paho.MqttConnectOptions) = connect(options, null, null)
    def connect(): paho.IMqttToken = connect(null, null, null)

    def disconnect(context: Any, l: paho.IMqttActionListener) = disconnect(30000, context, l)
    def disconnect(quiesce: Long): paho.IMqttToken = disconnect(quiesce, null, null)
    def disconnect(): paho.IMqttToken = disconnect(30000, null, null)


    def getClientId(): String = "a-client-id"
    def getPendingDeliveryTokens() = Array[paho.IMqttDeliveryToken]()
    def getServerURI(): String = "server-uri"

    def publish(x$1: String,x$2: paho.MqttMessage,x$3: Any,x$4: paho.IMqttActionListener): paho.IMqttDeliveryToken = ???
    def publish(x$1: String,x$2: paho.MqttMessage): paho.IMqttDeliveryToken = ???
    def publish(x$1: String,x$2: Array[Byte],x$3: Int,x$4: Boolean,x$5: Any,x$6: paho.IMqttActionListener): paho.IMqttDeliveryToken = ???
    def publish(x$1: String,x$2: Array[Byte],x$3: Int,x$4: Boolean): paho.IMqttDeliveryToken = ???
    def setCallback(x$1: paho.MqttCallback): Unit = {}
    def subscribe(x$1: Array[String],x$2: Array[Int],x$3: Any,x$4: paho.IMqttActionListener): paho.IMqttToken = ???
    def subscribe(x$1: Array[String],x$2: Array[Int]): paho.IMqttToken = ???
    def subscribe(x$1: String,x$2: Int,x$3: Any,x$4: paho.IMqttActionListener): paho.IMqttToken = ???
    def subscribe(x$1: String,x$2: Int): paho.IMqttToken = ???
    def unsubscribe(x$1: Array[String],x$2: Any,x$3: paho.IMqttActionListener): paho.IMqttToken = ???
    def unsubscribe(x$1: String,x$2: Any,x$3: paho.IMqttActionListener): paho.IMqttToken = ???
    def unsubscribe(x$1: Array[String]): paho.IMqttToken = ???
    def unsubscribe(x$1: String): org.eclipse.paho.client.mqttv3.IMqttToken = ???
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
