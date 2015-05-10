package uk.co.sprily
package mqtt
package rx
package util

import scala.util.control.ControlThrowable

import com.typesafe.scalalogging.LazyLogging

import _root_.rx.lang.scala.{Observable, Subscription}

trait ObservableImplicits extends LazyLogging {

  implicit class ObservableExtensions[T](source: Observable[T]) {

    def onUnsubscribe(f: => Unit): Observable[T] = {
      Observable.create { o =>
        val s = source.subscribe(o)
        Subscription {
          s.unsubscribe()
          try { f }
          catch {
            case t: ControlThrowable => throw t
            case t: Throwable => {
              logger.error(s"Caught whilst unsubscribing: ${t}")
            }
          }
        }
      }
    }

    def onSubscribe(f: => Unit): Observable[T] = {
      Observable.create { o =>
        try { f }
        catch {
          case t: ControlThrowable => throw t
          case t: Throwable => {
            logger.error(s"Caught whilst subscribing: ${t}")
            throw t
          }
        }
        source.subscribe(o)
      }
    }

  }
}

