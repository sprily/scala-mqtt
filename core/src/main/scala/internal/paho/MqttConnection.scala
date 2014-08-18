package uk.co.sprily
package mqtt
package internal
package paho

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{Future, future, Promise, promise}
import scala.concurrent.duration._

import scalaz._
import scalaz.contrib.std.scalaFuture._

// WARNING: this shadows uk.co.sprily.mqtt.internal.paho
import org.eclipse.paho.client.{mqttv3 => paho}

protected[mqtt] object PahoMqttConnection extends PahoMqttConnectionModule

protected[mqtt] trait PahoMqttConnectionModule extends MqttConnectionModule[Future] { self =>

  import scala.concurrent.ExecutionContext.Implicits.global

  override implicit def M = implicitly[Monad[Future]]

  def connect(options: MqttOptions): Future[MqttConnection] = {
    val client = new MqttConnection(
                  new paho.MqttAsyncClient(s"tcp://${options.host}:${options.port}",
                                           options.clientId.s),
                  options.pahoConnectOptions)

    client.initialiseConnection().map { _ => client }
  }

  def disconnect(conn: MqttConnection): Future[Unit] = {
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
  def attachConnectionHandler(conn: MqttConnection, callback: ConnectionHandler): HandlerToken = ???

  class MqttConnection(clientFactory: => paho.IMqttAsyncClient,
                       options: paho.MqttConnectOptions) {

    private[this] val client = {
      new AtomicReference[paho.IMqttAsyncClient](null)
    }

    private[paho] def closeConnection(): Future[Unit] = {
      ifActive { c =>
        val p = promise[Unit]

        c.isConnected match {
          case false => p.success(())
          case true  => c.disconnect(null, promiseAL(p))
        }

        p.future
      }
    }

    private[paho] def initialiseConnection(): Future[Unit] = {
      ifInactive { c =>
        c.setCallback(new paho.MqttCallback() {
          def connectionLost(t: Throwable): Unit = handleUnintendedDisconnection()
          def deliveryComplete(token: paho.IMqttDeliveryToken): Unit = println("Delivery Complete")
          def messageArrived(topic: String, msg: paho.MqttMessage): Unit = println("Msg Arrived")
        })

        // connect...
        val p = promise[Unit]
        c.connect(options, null, promiseAL(p))
        p.future
      }
    }

    @annotation.tailrec
    private[this] def reconnect(): Future[Unit] = {
      val oldClient = client.get
      if(oldClient == null) {
        Future.failed(new IllegalStateException("Active connection required"))
      } else {
        val newClient = clientFactory
        if (client.compareAndSet(oldClient, newClient)) {
          newClient.setCallback(new paho.MqttCallback() {
            def connectionLost(t: Throwable): Unit = handleUnintendedDisconnection()
            def deliveryComplete(token: paho.IMqttDeliveryToken): Unit = println("Delivery Complete")
            def messageArrived(topic: String, msg: paho.MqttMessage): Unit = println("Msg Arrived")
          })

          // connect...
          val p = promise[Unit]
          newClient.connect(options, null, promiseAL(p))
          p.future
        } else {
          reconnect()
        }
      }
    }

    private[this] def handleUnintendedDisconnection(delay: FiniteDuration = 0.seconds): Future[Unit] = {
      println("Handling disconnection")
      Thread.sleep(delay.toMillis)
      println("Finished sleeping")
      reconnect().recoverWith {
        case e: IllegalStateException => println("ISE: " + e) ; future { }
        case e: Exception => println("Recovering from " + e) ; handleUnintendedDisconnection(5.seconds)
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
          println(s"Promise already completed, ignoring...")
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
