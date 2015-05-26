package uk.co.sprily
package mqtt
package logback

import scala.beans.BeanProperty
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration._

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.classic.spi.ILoggingEvent

import mqtt._

class MqttAppender extends AppenderBase[ILoggingEvent] {

  type MqttClient = AsyncSimpleClient.Client

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Configured by Bean-setting, for compatibility with logback config file
    */
  @BeanProperty
  var layout: PatternLayout = _

  @BeanProperty
  var url: String = "tcp://127.0.0.1"

  @BeanProperty
  var port: Int = 1883

  @BeanProperty
  var username: String = _

  @BeanProperty
  var password: String = _

  @BeanProperty
  var mqttRoot: String = _

  private def options() = MqttOptions(
    url = this.url,
    port = this.port,
    clientId = ClientId.random(),
    cleanSession = true,
    username = noneIfNull(this.username),
    password = noneIfNull(this.password),
    keepAliveInterval = 60.seconds,
    InMemory)

  private var client: Option[MqttClient] = None

  override def start(): Unit = {

    if (layout == null) {
      addError(s"No layout set for the appender named [$name]")
      return
    }

    if (mqttRoot == null) {
      addError(s"No MQTT root topic set for the appender named [$name]")
      return
    }

    def tryConnect(): Future[MqttClient] = {
      AsyncSimpleClient.connect(options)
        .recoverWith {
          case (e: Exception) =>
            addError(s"Unable to connect to MQTT broker.  Re-trying. $e")
            Future { blocking { Thread.sleep(60000) } } flatMap (_ => tryConnect())
        }
    }

    addInfo("Attempting to connect to MQTT broker")
    tryConnect().onSuccess {
      case c =>
        addInfo(s"Successfully made initial connection to MQTT broker")
        client = Some(c)
    }
    super.start();
  }

  override def stop(): Unit = {
    client.foreach { c =>
      Await.ready(AsyncSimpleClient.disconnect(c), 10.seconds)
    }
  }

  override def append(event: ILoggingEvent): Unit = {
    client.foreach { c =>
      val s = layout.doLayout(event)
      AsyncSimpleClient.publish(
        c,
        Topic(s"""$mqttRoot/logs/${event.getLevel.toString}/${event.getLoggerName.replaceAllLiterally(".", "/")}"""),
        s.getBytes("UTF-8"),
        AtMostOnce,
        retain=false
      )
    }
  }

  private def noneIfNull[T](t: T) = if (t == null) None else Some(t)

}


