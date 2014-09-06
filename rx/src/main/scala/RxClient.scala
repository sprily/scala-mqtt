package uk.co.sprily
package mqtt
package rx

import scala.language.higherKinds
import scala.concurrent.Future

import com.typesafe.scalalogging.slf4j.StrictLogging

import _root_.rx.lang.scala.{Observable, Subject}
import _root_.rx.lang.scala.subjects.BehaviorSubject

import scalaz._
import scalaz.std.list._
import scalaz.std.map._
import scalaz.syntax.monad._
import scalaz.syntax.foldable._
import scalaz.syntax.monoid._
import scalaz.contrib.std.scalaFuture._

import util._

object AsynxRxClient extends RxClient[Future] {
  import scala.concurrent.ExecutionContext.Implicits.global
  val N = implicitly[Monad[Future]]
  protected lazy val connectionModule = mqtt.internal.PahoMqttConnection
}

trait RxClient[N[+_]] extends ClientModule[Observable, N]
                         with StrictLogging {

  implicit def N: Monad[N]

  protected val connectionModule: mqtt.internal.MqttConnectionModule[N]

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
      val status: Observable[ConnectionStatus]) {

    private[this] val dataSubject = Subject[(Topic, MqttMessage)]

    connectionModule.attachMessageHandler(connection, { (topic, msg) =>
      dataSubject.onNext((topic, msg))
    })

    private[this] val connLock = new AnyRef {}
    private[this] var currentSubscriptions = Map[TopicPattern,Int]()

    def data(topics: Seq[TopicPattern]): Observable[MqttMessage] = {

      val stream = dataSubject.filter { case (topic,msg) => topics.view.exists(_.matches(topic)) }
                              .map(_._2)
      stream.onSubscribe { subscribeTo(topics) }
            .onUnsubscribe { unsubscribeFrom(topics) }
    }

    private[this] def subscribeTo(topics: Seq[TopicPattern]) = {
      connLock.synchronized {
        val subs = topics.toList.foldMap { t => Map(t -> 1) }
        val newSubs = topics.filterNot(currentSubscriptions contains _)

        currentSubscriptions = subs |+| currentSubscriptions
        connectionModule.subscribe(connection, newSubs.map((_,AtMostOnce)))
      }
    }

    private[this] def unsubscribeFrom(topics: Seq[TopicPattern]) = {
      connLock.synchronized {
        val subs = topics.toList.foldMap { t => Map(t -> -1) }
        val nextSubs = subs |+| currentSubscriptions
        val unsubscribeFrom = nextSubs.toList.filter(_._2 == 0).map(_._1)

        connectionModule.unsubscribe(connection, unsubscribeFrom)
        currentSubscriptions = nextSubs.filter(_._2 > 0)
      }
    }

    def data(): Observable[MqttMessage] = dataSubject.map(_._2)
  }

  implicit val sumIntMonoid: Monoid[Int] = new Monoid[Int] {
    def zero = 0
    def append(i1: Int, i2: => Int) = i1 + i2
  }
}
