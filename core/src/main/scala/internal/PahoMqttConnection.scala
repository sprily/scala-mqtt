package uk.co.sprily
package mqtt
package internal

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{Future, future, Promise, promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.slf4j.StrictLogging

import scalaz._
import scalaz.syntax.monoid._
import scalaz.std.map._

import org.eclipse.paho.client.{mqttv3 => paho}

import util.AtomicOps

protected[mqtt] object PahoMqttConnection extends PahoMqttConnectionModule {
  override val ec = scala.concurrent.ExecutionContext.Implicits.global
}

protected[mqtt] trait PahoMqttConnectionModule extends MqttConnectionModule
                                                  with StrictLogging { self =>

  /** Module public interface **/

  override def connectWithHandler(options: MqttOptions,
                                  callbacks: Seq[ConnectionHandler]) = {
    val client = new MqttConnection(
                  new RestrictedPahoInterfaceImpl(
                    new paho.MqttAsyncClient(s"${options.url}:${options.port}",
                                             options.clientId.s)),
                  options.pahoConnectOptions)
    val tokens = callbacks.map(client.attachConnectionHandler(_))

    client.initialiseConnection().map { _ => (client, tokens) }
  }

  override def disconnect(conn: MqttConnection,
                          quiesce: FiniteDuration = 30.seconds) = conn.disconnect()

  override def publish(conn: MqttConnection,
                       topic: Topic,
                       payload: Seq[Byte],
                       qos: QoS,
                       retained: Boolean) = conn.publish(topic, payload, qos, retained)

  override def subscribe(conn: MqttConnection,
                         to: Seq[(TopicPattern,QoS)]) = conn.subscribe(to)

  override def unsubscribe(conn: MqttConnection,
                           topics: Seq[TopicPattern]) = conn.unsubscribe(topics)

  override def attachMessageHandler(conn: MqttConnection,
                                    callback: MessageHandler) = {
    conn.attachMessageHandler(callback)
  }

  override def attachConnectionHandler(conn: MqttConnection,
                                       callback: ConnectionHandler) = {
    conn.attachConnectionHandler(callback)
  }

  class MqttConnection(client: RestrictedPahoInterface,
                       options: paho.MqttConnectOptions) extends paho.MqttCallback {

    import MqttConnection._

    @volatile private[this] var connectionState: ConnState = Disconnected
    @volatile private[this] var active = false
    private[this] val connLock = new AnyRef {}

    /**
      * Subscriptions awaiting acknowledgment.
      *
      * The connection was alive when the `SUB` message was sent, but no `SUBACK`
      * has been received yet.  If during this period the connection is lost,
      * then the behaviour of the paho client is to:
      *
      *  - *not* re-send the original `SUB` message
      *  - *not* complete the underlying paho.MqttToken
      *
      * This `PahoMqttConnection` addresses this by tracking the in-flight
      * subscriptions, and upon recognising an unexpected disconnection, will
      * complete the associated `Future` with a failure.
      *
      */
    private[this] var inFlightSubscriptions = new AtomicReference[List[Promise[Unit]]](Nil)

    private[this] var activeSubscriptions = new AtomicReference[Map[TopicPattern,QoS]](Map())

    private[this] val connectionStatusSubscriptions = new NotificationHandler[ConnectionStatus]()
    private[this] val messageSubscriptions = new NotificationHandler[MqttMessage]()

    /** Disconnect from the broker.
      *
      * Fails if the connection is already inactive
      */
    def disconnect(quiesce: FiniteDuration = 30.seconds) = {
      connLock.synchronized {
        if (!active) {
          Future.failed(new InactiveConnectionException())
        } else {
          logger.debug("De-activating MqttConnection")
          active = false
          connectionState = Disconnecting

          val disconnect = {
            try {
              val p = promise[Unit]
              client.disconnect(quiesce.toMillis, promiseAL(p))
              p.future
            } catch {
              case e: paho.MqttException if alreadyDisconnected(e) => Future.successful({})
              case e: Exception => Future.failed(e)
            }
          }

          disconnect andThen {
            case _ => connLock.synchronized {
              client.close()
              connectionState = Disconnected
            }
          }
        }
      }
    }

    def publish(topic: Topic,
                payload: Seq[Byte],
                qos: QoS,
                retained: Boolean) = ???

    def subscribe(to: Seq[(TopicPattern, QoS)]): Future[Unit] = {
      logger.debug(s"Attempting to subscribe to: ${to}.  Awaiting lock first.")
      connLock.synchronized {
        if (!active) {
          logger.warn("Unable to subscribe since connection is inactive.")
          Future.failed(new ActiveConnectionException())
        } else {
          logger.debug(s"Attempting to subscribe to topics: ${to}")
          try {
            val p = promise[Unit]
            val (topics, qoss) = to.unzip
            client.subscribe(topics.map(_.path),
                             qoss.map(_.value),
                             promiseAL(p))
            logger.debug("Successfully sent subscription request")
            addInFlightSubscription(p)
            p.future andThen removeInFlightSubscription(p) andThen
                             addActiveSubscriptions(topics.zip(qoss))
          } catch {
            case e: Exception => {
              logger.warn(s"Error occurred attempting subscription: ${e}")
              Future.failed(e)
            }
          }
        }
      }
    }

    def unsubscribe(topics: Seq[TopicPattern]) = {

      logger.debug(s"Attempting to unsubscribe from ${topics}.  Awaiting lock first.")
      connLock.synchronized {
        if (!active) {
          logger.warn("Unable to unsubscribe since connection is inactive")
          Future.failed(new ActiveConnectionException())
        } else {
          logger.debug(s"Attempting to unsubscribing from: ${topics}")
          try {
            val p = promise[Unit]
            client.unsubscribe(topics.map(_.path),
                               promiseAL(p))
            logger.debug("Successfully sent un-subscription request")
            p.future andThen removeActiveSubscriptions(topics)
          } catch {
            case e: Exception => {
              logger.warn(s"Error occurred attempting un-subscription: ${e}")
              Future.failed(e)
            }
          }
        }
      }
    }

    def attachMessageHandler(callback: MessageHandler) = {
      messageSubscriptions.register(callback)
    }

    def attachConnectionHandler(callback: ConnectionHandler) = {
      connectionStatusSubscriptions.register(callback)
    }

    /** Connect for the first time.  Fails early **/
    private[internal] def initialiseConnection(): Future[Unit] = {
      logger.info("Attempting to intialise connection...")
      connLock.synchronized {
        if (active) {
          logger.warn("Connection initialisation failed since connection is inactive")
          Future.failed(new ActiveConnectionException())
        } else {
          logger.debug("Activating MqttConnection")
          active = true
          client.setCallback(this)
          
          connect() andThen {
            case Success(_) => handleClientConnected()
          }
        }
      }
    }

    /** Connect to the broker.
      *
      * Fails if the connection is still active or if the connection
      * attempt itself fails.
      */
    private[this] def connect(): Future[Unit] = {
      logger.debug("Attempting to connect to the broker.  Awaiting lock first")
      connLock.synchronized {
        if (!active) {
          logger.warn("Unable to connect to broker since connection is invalid.")
          Future.failed(new InactiveConnectionException())
        } else {
          try {
            connectionState = Connecting
            val p = promise[Unit]
            client.connect(options, promiseAL(p))
            logger.debug("Successfully initiated request to connect")
            p.future
          } catch {
            case e: Exception => {
              logger.warn(s"Attempt to connect failed: ${e}")
              Future.failed(e)
            }
          }
        }
      }
    }

    /**
      * Called whenever the underlying connection is successfully made.
      *
      * This includes the initial connection, as well as any subsequent
      * disconnections.
      */
    private[this] def handleClientConnected() = {
      connLock.synchronized {
        logger.info("Connected to MQTT broker")
        connectionState = Connected
        logger.debug("Notifying subscribers that we are connected")
        connectionStatusSubscriptions.notify(ConnectionStatus(true))
        resubscribeToActiveSubscriptions()
      }
    }

    /**
      * Called whenever the underlying connection is lost unexpectedly.
      */
    private[this] def handleUnexpectedDisconnect(t: Throwable) = {
      logger.debug("Handling unexpected disconnection.  Awaiting lock first.")
      connLock.synchronized {
        logger.warn(s"Unexpected disconnection from MQTT broker: ${t}")

        if (connectionState != Connected) {
          logger.warn(s"Not attempting to re-connect, current state: ${connectionState}")
        } else {
          logger.info(s"Handling unexpected disconnection from the broker.")
          connectionState = Disconnected
          connectionStatusSubscriptions.notify(ConnectionStatus(false))
          failAllInFlightSubscriptions(t)
          reconnect() andThen {
            case Success(_) => handleClientConnected()
          }
        }
      }
    }

    /** Repeatedly attempt to reconnect.
      *
      * Fails if the connection is de-activated in the mean time, otherwise
      * it just plugging away at it.
      */
    private[this] def reconnect(): Future[Unit] = {
      logger.debug("Attempting to reconnect to broker")
      connect() recoverWith {
        case e: paho.MqttException => {
          logger.debug(s"Re-connection attempt failed: ${e}")
          Thread.sleep(5.seconds.toMillis)    // TODO: non-blocking
          reconnect()
        }
        case e: InactiveConnectionException => {
          logger.debug("Re-connection aborted since client has been de-activated")
          Future.failed(e)
        }
        case e: Exception => {
          logger.error(s"Caught unexpected error re-connecting to broker: ${e}")
          Thread.sleep(5.seconds.toMillis)    // TODO: non-blocking
          reconnect()
        }
      }
    }

    /** Returns true if `e` indicates the connect was already broken **/
    private[this] def alreadyDisconnected(e: paho.MqttException) = {
      e.getReasonCode == paho.MqttException.REASON_CODE_CLIENT_ALREADY_DISCONNECTED 
    }

    private[this] def addInFlightSubscription(p: Promise[Unit]) = {
      logger.debug("Adding in-flight subscription")
      inFlightSubscriptions.update (p :: _)
    }

    private[this] def failAllInFlightSubscriptions(t: Throwable) = {
      val (ps, _) = inFlightSubscriptions.update { _ => Nil }

      logger.debug(s"Failing ${ps.length} in-flight subscriptions")
      // We don't really mind if the Promise has already been completed
      ps.foreach { p => p.tryFailure(new UnexpectedDisconnectionException(t)) }
    }

    private[this] def removeInFlightSubscription(p: Promise[Unit]): PartialFunction[Try[Unit], Unit] = {
      case _ => {
        logger.debug("Removing in-flight subscription")
        inFlightSubscriptions.update(_.filter(_ != p))
      }
    }

    private[this] def addActiveSubscriptions(ts: Seq[(TopicPattern, QoS)]): PartialFunction[Try[Unit], Unit] = {
      case Success(_) => {
        logger.debug(s"Adding active subscriptions: ${ts}")
        activeSubscriptions.update { topics => topics |+| ts.toMap }
        logger.debug(s"Active subscriptions after addition: ${activeSubscriptions.get}")
      }
    }

    private[this] def removeActiveSubscriptions(ts: Seq[TopicPattern]): PartialFunction[Try[Unit], Unit] = {
      case Success(_) => {
        logger.debug(s"Removing active subscriptions: ${ts}")
        activeSubscriptions.update { _.filterKeys { t => ! (ts contains t) } }
        logger.debug(s"Active subcriptions after removal: ${activeSubscriptions.get}")
      }
    }

    private[this] def resubscribeToActiveSubscriptions() = {

      logger.debug("Re-subscribing to active subscriptions if necessary")

      def resubscribe(): Future[Unit] = {
        val subs = activeSubscriptions.get.toList
        if (subs.nonEmpty) {
          logger.debug(s"Re-subscribing to: ${subs}")
          subscribe(subs) recoverWith {

            case e: InactiveConnectionException => {
              logger.debug("Re-subscription aborted since client has been de-activated")
              Future.failed(e)
            }

            case e: UnexpectedDisconnectionException => {
              logger.debug("Re-subscription aborted since client has disconnected unexpectedly")
              Future.failed(e)
            }

            case e: Exception => {
              logger.error(s"Caught error attempting to re-subscribe to active topics: ${e}")
              Thread.sleep(1.seconds.toMillis)
              resubscribe()
            }
          }
        } else {
          logger.debug(s"No active subscriptions to re-subscribe to: ${subs}")
          Future.successful(())
        }
      }

      if (options.isCleanSession) resubscribe()
    }

    /************ paho.MqttCallback implementation ************/
    def connectionLost(t: Throwable) = handleUnexpectedDisconnect(t)
    def deliveryComplete(token: paho.IMqttDeliveryToken): Unit = { }

    def messageArrived(topic: String, pMsg: paho.MqttMessage): Unit = {
      logger.debug(s"Message arrived at: ${topic}")
      val t = Topic(topic)
      val qos = QoS(pMsg.getQos).getOrElse {
        throw new IllegalArgumentException(s"Receieved invalid QOS value from broker: ${pMsg.getQos}")
      }
      val msg = MqttMessage(
        topic = t,
        payload = pMsg.getPayload.toVector,
        qos = qos,
        retained = pMsg.isRetained,
        dup = pMsg.isDuplicate)
      messageSubscriptions.notify(msg)
    }

  }
  
  object MqttConnection {
    private sealed trait ConnState
    private case object Connecting extends ConnState
    private case object Connected extends ConnState
    private case object Disconnecting extends ConnState
    private case object Disconnected extends ConnState
  }

  /**
    * Convenience interface through which paho is interfaced with.
    *
    * The paho `IMqttAsyncClient` interface is quite large.
    * `RestrictedPahoInterface` just pulls out what is necessary, this allows
    * for simpler testing as it's less work to fake this smaller interface.
    *
    * All method names are found in `paho.IMqttAsyncClient`.
    */
  protected trait RestrictedPahoInterface {
    def close(): Unit
    def connect(options: paho.MqttConnectOptions,
                listener: paho.IMqttActionListener): Unit
    def disconnect(quiesce: Long,
                   listener: paho.IMqttActionListener): Unit
    def setCallback(cb: paho.MqttCallback): Unit
    def subscribe(topics: Seq[String],
                  qos: Seq[Int],
                  listener: paho.IMqttActionListener): Unit
    def unsubscribe(topics: Seq[String],
                    listener: paho.IMqttActionListener): Unit
  }

  /**
    * The simple paho-backed implementation of RestrictedPahoInterface
    */
  protected class RestrictedPahoInterfaceImpl(c: paho.IMqttAsyncClient)
      extends RestrictedPahoInterface {

    def close() = c.close()
    def connect(options: paho.MqttConnectOptions,
                listener: paho.IMqttActionListener) = c.connect(options, null, listener)
    def disconnect(quiesce: Long,
                   listener: paho.IMqttActionListener) = c.disconnect(quiesce, null, listener)
    def setCallback(cb: paho.MqttCallback) = c.setCallback(cb)
    def subscribe(topics: Seq[String],
                  qos: Seq[Int],
                  listener: paho.IMqttActionListener) = {
      c.subscribe(topics.toArray, qos.toArray, null, listener)
    }
    def unsubscribe(topics: Seq[String], listener: paho.IMqttActionListener) = {
      c.unsubscribe(topics.toArray, null , listener)
    }
  }

  /** Module helper functions **/

  /**
    * Augments `MqttOptions` with additional helper methods.
    */
  implicit class MqttOptionsPahoOps(val options: MqttOptions) {
    def pahoConnectOptions: paho.MqttConnectOptions = {
      val pOpts = new paho.MqttConnectOptions()
      pOpts setCleanSession(options.cleanSession)
      pOpts setUserName(options.username.getOrElse(null))
      pOpts setPassword(options.password.map(_.toArray).getOrElse("".toArray))
      pOpts setKeepAliveInterval(options.keepAliveInterval.toSeconds.asInstanceOf[Int])
      pOpts
    }
  }

  private[this] def promiseAL(p: Promise[Unit]) = new paho.IMqttActionListener {

    def onSuccess(token: paho.IMqttToken): Unit = {
      if (!p.trySuccess(())) {
        logger.warn("Promise is already completed, ignoring...")
      }
    }

    def onFailure(token: paho.IMqttToken, t: Throwable): Unit = {
      if (!p.tryFailure(t)) {
        logger.warn("Promise is already completed, ignoring...")
      }
    }
  }


}
