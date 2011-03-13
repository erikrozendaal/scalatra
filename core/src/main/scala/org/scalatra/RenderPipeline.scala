package org.scalatra

import collection.mutable.ListBuffer
import java.io.{FileInputStream, File}
import util.using
import util.io.copy

/**
 * Allows overriding and chaining of response body rendering. Overrides [[ScalatraKernel#renderResponseBody]].
 */
trait RenderPipeline {this: ScalatraKernel =>

  // Use concurrency safe collection? Provide remove functionality?
  protected val renderPipeline = ListBuffer[PartialFunction[Any, Any]]()

  override def renderResponseBody(actionResult: Any) {
    var body = actionResult
    for (renderer <- renderPipeline if renderer.isDefinedAt(body)) {
      body = renderer(body)
    }
  }

  /**
   * Prepend a new renderer to the front of the render pipeline.
   */
  def render[A: Manifest](fun: A => Any) {
    new PartialFunction[Any, Any] {
      def apply(v1: Any) = fun(v1.asInstanceOf[A])
      def isDefinedAt(x: Any) = implicitly[Manifest[A]].erasure.isInstance(x)
    } +=: renderPipeline
  }

  render[Any] {
    case _: Unit => // If an action or renderer returns Unit, it assumes responsibility for the response
    case x => response.getWriter.print(x.toString)
  }

  render[File] {file =>
    using(new FileInputStream(file)) {in => copy(in, response.getOutputStream)}
  }

  render[Array[Byte]] {bytes =>
    response.getOutputStream.write(bytes)
  }
}
