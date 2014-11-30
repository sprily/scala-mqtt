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

  override implicit val ec = scala.concurrent.ExecutionContext.Implicits.global

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

  "A MqttConnection" should "protect itself from disconnection handler being called twice in succession" in {

    val failureLatch = promise[Unit]

    val fake = new FakePahoAsyncClient with SuccessfulDisconnection {

      var numConnectCalls = 0

      override def connect(options: paho.MqttConnectOptions,
                           listener: paho.IMqttActionListener) = {

        numConnectCalls += 1
        numConnectCalls match {
          case 1 => succeedListener(listener)
          case 2 => new FakeMqttToken()
          case 3 => failureLatch.success(()) ; new FakeMqttToken()
        }
      }
    }

    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)

    Await.ready(client.initialiseConnection, 1.seconds)
    client.connectionLost(new java.io.IOException("First failure"))
    client.connectionLost(new java.io.IOException("Second failure"))

    intercept[java.util.concurrent.TimeoutException] {
      Await.result(failureLatch.future, 1.seconds)
    }
  }

  "A MqttConnection" should "reject subscriptions when inactive" in {
    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)

    val f = client.initialiseConnection andThen {
      case _ => client.disconnect()
    }
    Await.result(f, 5.seconds)

    intercept[ActiveConnectionException] {
      Await.result(client.subscribe(Nil), 5.seconds)
    }
  }

  "A MqttConnection" should "fail subscriptions if underlying connection is broken" in {

    // client which rejects subscriptions immediately
    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection {
      override def subscribe(topics: Seq[String], qos: Seq[Int], listener: paho.IMqttActionListener) = {
        listener.onFailure(new FakeMqttToken(), new java.io.IOException("broken connection"))
      }
    }

    // connect
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)
    Await.ready(client.initialiseConnection(), 5.seconds)

    // attempt subscription
    intercept[java.io.IOException] {
      Await.result(client.subscribe(Nil), 5.seconds)
    }
  }

  "A MqttConnection" should "fail in-flight subscriptions if underlying connection breaks mid-flight" in {

    // client which never completes the task
    // this is to simulate how the paho client *actually* behaves when the underlying
    // connection breaks while a `SUB` message is still in-flight, ie 
    //
    //  - the `SUB` message is sent, and the `subscribe()` method call returns
    //  - the connection breaks
    //  - the connection is re-established
    //  - the but the original `SUB` is never re-sent.
    //  - so the paho token is never completed.
    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection {

      private var subRcvd = 0

      // Allow the first subscription to succeed, but "hang" on the rest of them
      override def subscribe(topics: Seq[String],
                             qos: Seq[Int],
                             listener: paho.IMqttActionListener) = {
        subRcvd match {
          case 0 => listener.onSuccess(new FakeMqttToken())
          case _ => { }
        }
        subRcvd += 1
      }
    }

    // connect
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)
    Await.ready(client.initialiseConnection(), 5.seconds)

    // subscription should successfully return an un-completed Future
    val f1 = client.subscribe(Nil)
    val f2 = client.subscribe(Nil)

    // simulate disconnection through callback interface
    client.connectionLost(new java.io.IOException("Uh oh"))

    // Check that f1 successfully subscribed
    Await.ready(f1, 1.seconds)

    // And f2 failed
    intercept[java.io.IOException] {
      Await.result(f2, 1.seconds)
    }
  }

  "A MqttConnection" should "forward messages to subscribed handlers" in {
    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection
    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)
    Await.ready(client.initialiseConnection(), 1.seconds)

    var called = false
    val token = client.attachMessageHandler { msg =>
      called = true
      msg.topic should equal (Topic("topic"))
      msg.payload should equal (List[Byte](0x00, 0x10))
      msg.qos should equal (AtLeastOnce)
      msg.retained should equal (false)
      msg.dup should equal (false)
    }

    // simulate msg through callback interface
    val msg = new paho.MqttMessage(List[Byte](0x00, 0x10).toArray)
    client.messageArrived("topic", msg)

    called should equal (true)
  }

  "A MqttConnection" should "re-subscribe upon un-expected disconnections" in {
    import scala.language.reflectiveCalls
    val latch = promise[Unit]
    val topics = List(TopicPattern("one"), TopicPattern("two"))
    val qoss   = List(AtLeastOnce, AtMostOnce)
    val subs   = topics zip qoss

    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection {

      var numSubCalls = 0
      var resubscriptions: Option[(Seq[String], Seq[Int])] = None

      override def subscribe(topics: Seq[String],
                             qos: Seq[Int],
                             listener: paho.IMqttActionListener) = {

        numSubCalls += 1

        numSubCalls match {
          case 1 => listener.onSuccess(new FakeMqttToken())
          case 2 => listener.onSuccess(new FakeMqttToken())
          case 3 => listener.onFailure(new FakeMqttToken(), new java.io.IOException("Something went wrong"))
          case 4 => {
            resubscriptions = Some((topics, qos))
            listener.onSuccess(new FakeMqttToken())
            latch.success(())
          }
        }
      }
    }

    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)
    Await.ready(client.initialiseConnection, 1.seconds)
    Await.ready(client.subscribe(subs), 1.seconds)
    Await.ready(client.subscribe(topics zip List(AtMostOnce, ExactlyOnce)), 1.seconds)
    
    // simulate disconnection
    client.connectionLost(new java.io.IOException("Uh oh"))

    Await.ready(latch.future, 3.seconds)
    fake.resubscriptions should not equal None
    fake.resubscriptions.get._1 should equal (topics.map(_.path))
    fake.resubscriptions.get._2 should equal (List(AtLeastOnce, ExactlyOnce).map(_.value))
  }

  "A MqttConnection" should "re-subscribe *through* disconnections" in {

    val latch = promise[Unit]
    val failureLatch = promise[Unit]
    val disconnectLatch = promise[Unit]
    val topics = List(TopicPattern("one"), TopicPattern("two"))
    val qoss   = List(AtLeastOnce, AtMostOnce)
    val subs   = topics zip qoss

    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection {

      var numSubCalls = 0

      override def subscribe(topics: Seq[String],
                             qos: Seq[Int],
                             listener: paho.IMqttActionListener) = {

        numSubCalls += 1

        numSubCalls match {
          case 1 => listener.onSuccess(new FakeMqttToken())
          case 2 => disconnectLatch.success(()) // note listener is not completed
          case 3 => listener.onSuccess(new FakeMqttToken()) ; latch.success(())
          case _ => listener.onSuccess(new FakeMqttToken()) ; failureLatch.success(())
        }
      }
    }

    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)
    Await.ready(client.initialiseConnection, 3.seconds)
    Await.ready(client.subscribe(subs), 3.seconds)
    
    // simulate disconnection
    client.connectionLost(new java.io.IOException("Uh oh"))

    // wait for disconnectLatch to disconnect again
    Await.ready(disconnectLatch.future, 3.seconds)
    client.connectionLost(new java.io.IOException("Not again"))

    Await.ready(latch.future, 3.seconds)
    intercept[java.util.concurrent.TimeoutException] {
      Await.ready(failureLatch.future, 3.seconds)
    }
  }

  "A MqttConnection" should "unsubscribe from topics" in {
    import scala.language.reflectiveCalls

    val topics = List(TopicPattern("one"), TopicPattern("two"))
    val qoss   = List(AtLeastOnce, AtMostOnce)
    val subs   = topics zip qoss

    val reSubscribedLatch = promise[Unit]
    val fake = new FakePahoAsyncClient with SuccessfulConnection with SuccessfulDisconnection {

      var subs   = List[String]()
      var unsubs = List[String]()
      var subsCalls = 0

      override def subscribe(topics: Seq[String],
                             qos: Seq[Int],
                             listener: paho.IMqttActionListener) = {
        subsCalls += 1
        subs = subs ++ topics
        listener.onSuccess(new FakeMqttToken())
        if (subsCalls == 2) { reSubscribedLatch.success(()) }
      }

      override def unsubscribe(topics: Seq[String],
                               listener: paho.IMqttActionListener) = {
        unsubs = unsubs ++ topics
        listener.onSuccess(new FakeMqttToken())
      }
    }

    val client = new MqttConnection(fake, defaultOptions.pahoConnectOptions)
    Await.ready(client.initialiseConnection, 3.seconds)
    Await.ready(client.subscribe(subs), 3.seconds)
    Await.ready(client.unsubscribe(topics.tail), 3.seconds)

    // force unexpected disconnection, and await re-subscriptions after
    // successful reconnection
    client.connectionLost(new java.io.IOException("Uh oh"))
    Await.ready(reSubscribedLatch.future, 3.seconds)

    fake.subs should equal ((topics ++ List(topics.head)).map(_.path))
    fake.unsubs should equal (topics.tail.map(_.path))

  }

  val defaultOptions = MqttOptions.cleanSession()

  trait FakeClientHelpers {
    protected def succeedListener(listener: paho.IMqttActionListener) = {
      listener.onSuccess(new FakeMqttToken())
    }

    protected def failListener(listener: paho.IMqttActionListener) = {
      listener.onFailure(
        new FakeMqttToken(),
        new java.io.IOException("something went wrong"))
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
                l: paho.IMqttActionListener): Unit
    def disconnect(quiesce: Long,
                   l: paho.IMqttActionListener): Unit
    def setCallback(cb: paho.MqttCallback): Unit = { }
    def subscribe(topics: Seq[String],
                  qos: Seq[Int],
                  listener: paho.IMqttActionListener) = { }
    def unsubscribe(topics: Seq[String],
                    listener: paho.IMqttActionListener) = { }
    def publish(topic: Topic,
                payload: Seq[Byte],
                qos: QoS,
                retain: Boolean,
                listener: paho.IMqttActionListener) = { }
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
    def getGrantedQos(): Array[Int] = ???
    def getResponse = ???
    def getSessionPresent = ???
  }

}
