package uk.co.sprily
package mqtt
package rx

import scala.language.higherKinds
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.typesafe.scalalogging.LazyLogging

import _root_.rx.lang.scala.{Observable, Subject}
import _root_.rx.lang.scala.subjects.BehaviorSubject

import scalaz._
import scalaz.std.list._
import scalaz.std.map._
import scalaz.syntax.monad._
import scalaz.syntax.foldable._
import scalaz.syntax.monoid._

import util._

object AsyncRxClient extends RxClient {
  val ec = scala.concurrent.ExecutionContext.Implicits.global
  protected lazy val connectionModule = mqtt.internal.PahoMqttConnection
}

trait RxClient extends ClientModule[Observable]
                  with LazyLogging {

  implicit val ec: ExecutionContext
  protected val connectionModule: mqtt.internal.MqttConnectionModule

  override def connect(options: MqttOptions) = {
    val statusSubject = BehaviorSubject(ConnectionStatus(false))
    val callback = (status: ConnectionStatus) => { statusSubject.onNext(status) }
    connectionModule.connectWithHandler(options, List(callback)).map { case (conn, _) =>
      new Client(conn, statusSubject)
    }
  }

  override def disconnect(client: Client) = connectionModule.disconnect(client.connection)

  override def status(client: Client) = {
    client.status
  }

  override def data(client: Client) = client.data()
  override def data(client: Client, topics: Seq[TopicPattern]) = client.data(topics)

  override def publish(client: Client, topic: Topic, payload: Array[Byte], qos: QoS, retain: Boolean = false) = ???

  class Client(
      private[rx] val connection: connectionModule.MqttConnection,
      val connectionStatus: Observable[ConnectionStatus]) extends ClientLike {

    private[this] val dataSubject = Subject[MqttMessage]

    connectionModule.attachMessageHandler(connection, dataSubject.onNext _)

    private[this] val connLock = new AnyRef {}
    private[this] var currentSubscriptions = Map[TopicPattern,Int]()

    def data(topics: Seq[TopicPattern]): Observable[MqttMessage] = {

      val stream = dataSubject.filter { case msg => topics.view.exists(_.matches(msg.topic)) }
      stream.onSubscribe { subscribeTo(topics) }
            .onUnsubscribe { unsubscribeFrom(topics) }
    }

    private[this] def subscribeTo(topics: Seq[TopicPattern]) = {
      logger.debug(s"Client attempting to subscribe to topics: ${topics}")
      connLock.synchronized {
        val subs = topics.toList.foldMap { t => Map(t -> 1) }
        val newSubs = topics.filterNot(currentSubscriptions contains _)

        logger.debug(s"Current subscriptions before: ${currentSubscriptions}")
        currentSubscriptions = subs |+| currentSubscriptions
        logger.debug(s"Current subscriptions after: ${currentSubscriptions}")
        logger.debug(s"New subscriptions: ${newSubs}")
        connectionModule.subscribe(connection, newSubs.map((_,AtMostOnce)))
      }
    }

    private[this] def unsubscribeFrom(topics: Seq[TopicPattern]) = {
      logger.debug(s"Client attempting to un-subscribe from: ${topics}")
      connLock.synchronized {
        val subs = topics.toList.foldMap { t => Map(t -> -1) }
        logger.debug(s"Current subscriptions before: ${currentSubscriptions}")
        val nextSubs = subs |+| currentSubscriptions
        val unsubscribeFrom = nextSubs.toList.filter(_._2 == 0).map(_._1)
        logger.debug(s"Need to unsubscribe from: ${unsubscribeFrom}")

        connectionModule.unsubscribe(connection, unsubscribeFrom)
        currentSubscriptions = nextSubs.filter(_._2 > 0)
        logger.debug(s"Current subscriptions after: ${currentSubscriptions}")
      }
    }

    override def data(): Observable[MqttMessage] = dataSubject
  }

  implicit val sumIntMonoid: Monoid[Int] = new Monoid[Int] {
    def zero = 0
    def append(i1: Int, i2: => Int) = i1 + i2
  }
}
