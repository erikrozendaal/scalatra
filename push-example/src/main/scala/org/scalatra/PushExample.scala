package org.scalatra

import scalate.ScalateSupport
import org.atmosphere.cpr.{AtmosphereResourceEvent, AtmosphereResourceEventListener, Meteor}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import org.atmosphere.util.XSSHtmlFilter
import collection.JavaConversions._

class EventsLogger extends AtmosphereResourceEventListener {

  def onSuspend(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse] ){
    println("onSuspend: " + event);
  }

  def onResume(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse] ){
    println("onResume: " + event);
  }

  def onDisconnect(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse] ){
    println("onDisconnect: " + event);
  }

  def onBroadcast(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse] ){
    println("onBroadcast: " + event);
  }
}

class PushExample extends ScalatraServlet with ScalateSupport {

  val list = List(new XSSHtmlFilter)

  get("/") {
    contentType = "text/html"
    renderTemplate("index.ssp")
  }

  get("/pubsub/:topic") {
    println("received request for %s" format params("topic"))
    val m = Meteor.build(request, list, null)
    m.addListener(new EventsLogger)
    session("meteor") = m
    contentType = "text/html;charset=UTF-8"
    m.suspend(-1)
    halt
  }

  post("/pubsub/:topic") {
    println("publishing message [%s] to topic [%s]".format(params("message"), params("topic")))
    session("meteor") foreach { _.asInstanceOf[Meteor].broadcast(params("message"))}
    halt
  }
}