package org.scalatra.atmosphere

import org.scalatra.{Initializable, Handler}
import org.atmosphere.cpr.AtmosphereResourceEventListener
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.servlet.ServletConfig

trait ScalatraPushServlet extends Handler with Initializable with AtmosphereResourceEventListener {

  abstract override def handle(req: HttpServletRequest, res: HttpServletResponse) {
    super.handle(req, res)
  }

  type Config = ServletConfig

  def init(config: ServletConfig) = {
  }


}