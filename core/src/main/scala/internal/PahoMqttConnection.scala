package uk.co.sprily
package mqtt
package internal

import scala.concurrent.{Future, future, Promise, promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.slf4j.StrictLogging

import scalaz._
import scalaz.contrib.std.scalaFuture._

import org.eclipse.paho.client.{mqttv3 => paho}

protected[mqtt] object PahoMqttConnection extends PahoMqttConnectionModule

protected[mqtt] trait PahoMqttConnectionModule extends MqttConnectionModule[Future]
                                                  with DefaultConnectionHandling[Future]
                                                  with StrictLogging { self =>

  import scala.concurrent.ExecutionContext.Implicits.global
  override implicit def M = implicitly[Monad[Future]]

  /** Module public interface **/

  override def connectWithHandler(options: MqttOptions,
                                  callbacks: Seq[ConnectionHandler]) = {
    val client = new MqttConnection(
                  new RestrictedPahoInterfaceImpl(
                    new paho.MqttAsyncClient(s"tcp://${options.host}:${options.port}",
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
                         topics: Seq[TopicPattern],
                         qos: QoS) = conn.subscribe(topics, qos)

  override def unsubscribe(conn: MqttConnection,
                           topics: Seq[Topic]) = conn.unsubscribe(topics)

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

    @volatile private[this] var active = false
    private[this] val connLock = new AnyRef {}
    private[this] val connectionStatusSubscriptions = new NotificationHandler[ConnectionStatus]()

    def disconnect(quiesce: FiniteDuration = 30.seconds) = {
      connLock.synchronized {
        if (!active) {
          Future.failed(new InactiveConnectionException())
        } else {
          logger.debug("De-activating MqttConnection")
          active = false

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
            case _ => client.close()
          }
        }
      }
    }

    def publish(topic: Topic,
                payload: Seq[Byte],
                qos: QoS,
                retained: Boolean) = ???

    def subscribe(topics: Seq[TopicPattern], qos: QoS) = ???
    def unsubscribe(topics: Seq[Topic]) = ???
    def attachMessageHandler(callback: MessageHandler) = ???
    def attachConnectionHandler(callback: ConnectionHandler) = ???

    /** Connect for the first time.  Fails early **/
    private[internal] def initialiseConnection(): Future[Unit] = {
      connLock.synchronized {
        if (active) {
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
      connLock.synchronized {
        if (!active) {
          Future.failed(new InactiveConnectionException())
        } else {
          try {
            val p = promise[Unit]
            client.connect(options, promiseAL(p))
            p.future
          } catch {
            case e: Exception => Future.failed(e)
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
      logger.info("Connected to MQTT broker")
      connectionStatusSubscriptions.notify(ConnectionStatus(true))
    }

    /**
      * Called whenever the underlying connection is lost unexpectedly.
      */
    private[this] def handleUnexpectedDisconnect() = {
      logger.warn("Unexpected disconnection from MQTT broker")
      connectionStatusSubscriptions.notify(ConnectionStatus(false))
      reconnect() andThen {
        case Success(_) => handleClientConnected()
      }
    }

    /** Repeatedly attempt to reconnect.
      *
      * Fails if the connection is de-activated in the mean time, otherwise
      * it just plugging away at it.
      */
    private[this] def reconnect(): Future[Unit] = {
        connect() recoverWith {
          case e: paho.MqttException => {
            logger.debug(s"Re-connection attempt failed: ${e}")
            Thread.sleep(5.seconds.toMillis)
            reconnect()
          }
          case e: InactiveConnectionException => {
            logger.debug("Re-connection aborted since client has been de-activated")
            Future.failed(e)
          }
          case e: Exception => {
            logger.error(s"Caught unexpected error re-connecting to broker: ${e}")
            Thread.sleep(5.seconds.toMillis)
            reconnect()
          }
        }
    }

    /** Returns true if `e` indicates the connect was already broken **/
    private[this] def alreadyDisconnected(e: paho.MqttException) = {
      e.getReasonCode == paho.MqttException.REASON_CODE_CLIENT_ALREADY_DISCONNECTED 
    }

    /************ paho.MqttCallback implementation ************/
    def connectionLost(t: Throwable) = handleUnexpectedDisconnect()
    def deliveryComplete(token: paho.IMqttDeliveryToken): Unit = ???
    def messageArrived(topic: String, msg: paho.MqttMessage): Unit = ???


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
                listener: paho.IMqttActionListener): paho.IMqttToken
    def disconnect(quiesce: Long,
                   listener: paho.IMqttActionListener): paho.IMqttToken
    def setCallback(cb: paho.MqttCallback): Unit
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
  }

  /** Module helper functions **/

  /**
    * Augments `MqttOptions` with additional helper methods.
    */
  implicit class MqttOptionsPahoOps(val options: MqttOptions) {
    def pahoConnectOptions: paho.MqttConnectOptions = {
      val pOpts = new paho.MqttConnectOptions()
      pOpts setCleanSession(options.cleanSession)
      pOpts
    }
  }

  private[this] def promiseAL(p: Promise[Unit]) = new paho.IMqttActionListener {
    def onSuccess(token: paho.IMqttToken): Unit = {
      if (p.isCompleted) {
        // paho client will call callback twice on some occasions
        logger.warn("Promise is already completed, ignoring...")
      } else {
        p.success(())
      }
    }
    def onFailure(token: paho.IMqttToken, t: Throwable): Unit = {
      p.failure(t)
    }
  }

}
