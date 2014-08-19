package uk.co.sprily
package mqtt
package internal

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{Future, future, Promise, promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.slf4j.StrictLogging

import scalaz._
import scalaz.contrib.std.scalaFuture._

// WARNING: this shadows uk.co.sprily.mqtt.internal.paho
import org.eclipse.paho.client.{mqttv3 => paho}

protected[mqtt] object PahoMqttConnection extends PahoMqttConnectionModule

protected[mqtt] trait PahoMqttConnectionModule extends MqttConnectionModule[Future]
                                                  with DefaultConnectionHandling[Future]
                                                  with StrictLogging { self =>

  import scala.concurrent.ExecutionContext.Implicits.global

  override implicit def M = implicitly[Monad[Future]]

  def connectWithHandler(options: MqttOptions,
                         callbacks: Seq[ConnectionHandler]) = {
    val client = new MqttConnection(
                  new paho.MqttAsyncClient(s"tcp://${options.host}:${options.port}",
                                           options.clientId.s),
                  options.pahoConnectOptions)

    client.initialiseConnection().map { _ => (client, Nil) }
  }

  def disconnect(conn: MqttConnection, quiesce: FiniteDuration = 30.seconds): Future[Unit] = {
    conn.closeConnection()
  }

  def publish(conn: MqttConnection,
              topic: Topic,
              payload: Seq[Byte],
              qos: QoS,
              retained: Boolean): Future[Unit] = ???
  def subscribe(conn: MqttConnection, topics: Seq[TopicPattern], qos: QoS): Future[Unit] = ???
  def unsubscribe(conn: MqttConnection, topics: Seq[Topic]): Future[Unit] = ???

  /** Notifications of incoming messages **/
  def attachMessageHandler(conn: MqttConnection, callback: MessageHandler): HandlerToken = ???

  /** Notifications of the connection status **/
  def attachConnectionHandler(conn: MqttConnection, callback: ConnectionHandler): HandlerToken = {
    conn.attachConnectionHandler(callback)
  }

  class MqttConnection(clientFactory: => paho.IMqttAsyncClient,
                       options: paho.MqttConnectOptions) {

    val connectionSubscriptions = new NotificationHandler[ConnectionStatus]()

    private[this] val client = {
      new AtomicReference[paho.IMqttAsyncClient](null)
    }

    def attachConnectionHandler(callback: ConnectionHandler): HandlerToken = {
      connectionSubscriptions.register(callback)
    }

    private[internal] def closeConnection(): Future[Unit] = {
      logger.debug("Attempting to close connection to broker.")
      ifActive { c =>
        val p = promise[Unit]

        logger.debug(s"Closing connection.  Underlying connection was open: ${c.isConnected}")
        c.isConnected match {
          case false => p.success(())
          case true  => c.disconnect(null, promiseAL(p))
        }

        p.future
      }
    }

    private[internal] def initialiseConnection(): Future[Unit] = {
      logger.debug("Initialising connection")
      ifInactive { connectWith(_) }
    }

    @annotation.tailrec
    private[this] def reconnect(): Future[Unit] = {
      logger.debug("Attempting to re-connect to broker")
      val oldClient = client.get
      if(oldClient == null) {
        Future.failed(new IllegalStateException("Active connection required"))
      } else {
        val newClient = clientFactory
        if (client.compareAndSet(oldClient, newClient)) {
          connectWith(newClient)
        } else {
          reconnect()
        }
      }
    }

    private[this] def connectWith(c: paho.IMqttAsyncClient): Future[Unit] = {
      logger.debug("Setting up connection callbacks")
      c.setCallback(new paho.MqttCallback() {
        def connectionLost(t: Throwable): Unit = handleUnintendedDisconnection()
        def deliveryComplete(token: paho.IMqttDeliveryToken): Unit = println("Delivery Complete")
        def messageArrived(topic: String, msg: paho.MqttMessage): Unit = println("Msg Arrived")
      })

      logger.debug("Calling connect() on underlying client")
      val p = promise[Unit]
      c.connect(options, null, promiseAL(p))
      p.future.andThen {
        case Success(_) => connectionSubscriptions.notify(ConnectionStatus(true))
        case Failure(_) => connectionSubscriptions.notify(ConnectionStatus(false))
      }
    }

    private[this] def handleUnintendedDisconnection(delay: FiniteDuration = 0.seconds): Future[Unit] = {

      connectionSubscriptions.notify(ConnectionStatus(false))
      logger.info("Handling unexpected disconnection from MQTT broker")
      logger.debug(s"Sleeping for ${delay} before attempting initial re-connection")
      Thread.sleep(delay.toMillis)

      def reAttempt(): Future[Unit] = {
        reconnect().recoverWith {
          case e: IllegalStateException => {
            logger.debug("Re-connection halted as the connection is now inactive")
            future { }
          }
          case e: paho.MqttException => {
            logger.debug(s"Re-connection attempt failed: ${e}")
            Thread.sleep(5.seconds.toMillis)
            reAttempt()
          }
        }
      }

      reAttempt().andThen {
        case Success(_) => logger.info("Successfully re-connected to broker")
      }
    }

    @annotation.tailrec
    private[this] def ifActive(f: paho.IMqttAsyncClient => Future[Unit]): Future[Unit] = {
      val oldClient = client.get
      if (oldClient == null) {
        Future.failed(new IllegalStateException("Active connection required."))
      } else {
        if (client.compareAndSet(oldClient, null)) {
          f(oldClient)
        } else {
          ifActive(f)
        }
      }
    }

    @annotation.tailrec
    private[this] def ifInactive(f: paho.IMqttAsyncClient => Future[Unit]): Future[Unit] = {
      if (client.get != null) {
        Future.failed(new IllegalStateException("Inactive connection required."))
      } else {
        val newClient = clientFactory
        if (client.compareAndSet(null, newClient)) {
          f(newClient)
        } else {
          ifInactive(f)
        }
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

  implicit class MqttOptionsPahoOps(val options: MqttOptions) {
    def pahoConnectOptions: paho.MqttConnectOptions = {
      val pOpts = new paho.MqttConnectOptions()
      pOpts setCleanSession(options.cleanSession)
      pOpts
    }
  }
}
