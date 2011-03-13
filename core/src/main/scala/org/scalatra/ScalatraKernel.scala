package org.scalatra

import javax.servlet._
import javax.servlet.http._
import scala.util.DynamicVariable
import scala.util.matching.Regex
import scala.collection.JavaConversions._
import scala.collection.mutable.{ConcurrentMap, HashMap, ListBuffer}
import scala.xml.NodeSeq
import util.{MapWithIndifferentAccess, MultiMapHeadView, using}
import util.io.copy
import java.io.{File, FileInputStream}
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec

object ScalatraKernel
{
  type MultiParams = Map[String, Seq[String]]

  type Action = () => Any

  val httpMethods = List("GET", "POST", "PUT", "DELETE", "OPTIONS")
  val writeMethods = "POST" :: "PUT" :: "DELETE" :: Nil
  val csrfKey = "csrfToken"

  val EnvironmentKey = "org.scalatra.environment"
}
import ScalatraKernel._

/**
 * ScalatraKernel provides the DSL for building Scalatra applications.
 *
 * At it's core a type mixing in ScalatraKernel is a registry of possible actions,
 * every request is dispatched to the first route matching.
 *
 * The [[org.scalatra.ScalatraKernel#get]], [[org.scalatra.ScalatraKernel#post]],
 * [[org.scalatra.ScalatraKernel#put]] and [[org.scalatra.ScalatraKernel#delete]]
 * methods register a new action to a route for a given HTTP method, possibly
 * overwriting a previous one. This trait is thread safe.
 */
trait ScalatraKernel extends Handler with Initializable
{
  protected val Routes: ConcurrentMap[String, List[Route]] = {
    val map = new ConcurrentHashMap[String, List[Route]]
    httpMethods foreach { x: String => map += ((x, List[Route]())) }
    map
  }

  def contentType = response.getContentType
  def contentType_=(value: String): Unit = response.setContentType(value)

  protected val defaultCharacterEncoding = "UTF-8"
  protected val _response   = new DynamicVariable[HttpServletResponse](null)
  protected val _request    = new DynamicVariable[HttpServletRequest](null)

  protected implicit def requestWrapper(r: HttpServletRequest) = RichRequest(r)
  protected implicit def sessionWrapper(s: HttpSession) = new RichSession(s)
  protected implicit def servletContextWrapper(sc: ServletContext) = new RichServletContext(sc)

  protected[scalatra] class Route(val routeMatchers: Iterable[RouteMatcher], val action: Action) {
    def apply(realPath: String): Option[Any] = RouteMatcher.matchRoute(routeMatchers) flatMap { invokeAction(_) }

    private def invokeAction(routeParams: MultiParams) =
      _multiParams.withValue(multiParams ++ routeParams) {
        try {
          Some(action.apply())
        }
        catch {
          case e: PassException => None
        }
      }

    override def toString() = routeMatchers.toString
  }

  /**
   * Pluggable way to convert Strings into RouteMatchers.  By default, we
   * interpret them the same way Sinatra does.
   */
  protected implicit def string2RouteMatcher(path: String): RouteMatcher =
    SinatraPathPatternParser(path)

  /**
   * Path pattern is decoupled from requests.  This adapts the PathPattern to
   * a RouteMatcher by supplying the request path.
   */
  protected implicit def pathPatternParser2RouteMatcher(pattern: PathPattern): RouteMatcher =
    new RouteMatcher {
      def apply() = pattern(requestPath)

      // By overriding toString, we can list the available routes in the
      // default notFound handler.
      override def toString = pattern.regex.toString
    }

  protected implicit def regex2RouteMatcher(regex: Regex): RouteMatcher = new RouteMatcher {
    def apply() = regex.findFirstMatchIn(requestPath) map { _.subgroups match {
      case Nil => Map.empty
      case xs => Map("captures" -> xs)
    }}

    override def toString = regex.toString
  }

  protected implicit def booleanBlock2RouteMatcher(matcher: => Boolean): RouteMatcher =
    () => { if (matcher) Some(Map[String, Seq[String]]()) else None }

  def handle(request: HttpServletRequest, response: HttpServletResponse) {
    // As default, the servlet tries to decode params with ISO_8859-1.
    // It causes an EOFException if params are actually encoded with the other code (such as UTF-8)
    if (request.getCharacterEncoding == null)
      request.setCharacterEncoding(defaultCharacterEncoding)

    val realMultiParams = request.getParameterMap.asInstanceOf[java.util.Map[String,Array[String]]].toMap
      .transform { (k, v) => v: Seq[String] }

    response.setCharacterEncoding(defaultCharacterEncoding)

    _request.withValue(request) {
      _response.withValue(response) {
        _multiParams.withValue(Map() ++ realMultiParams) {
          try {
            beforeFilters foreach { _() }
            renderResponse(Routes(effectiveMethod).toStream.flatMap { _(requestPath) }.headOption.getOrElse(doNotFound()))
          }
          catch {
            case HaltException(Some(code), Some(msg)) => response.sendError(code, msg)
            case HaltException(Some(code), None) => response.sendError(code)
            case HaltException(None, _) =>
            case e => renderResponse(handleError(e))
          } finally {
            afterFilters foreach { _() }
          }
        }
      }
    }
  }

  protected def effectiveMethod = request.getMethod.toUpperCase match {
    case "HEAD" => "GET"
    case x => x
  }

  def requestPath: String

  protected val beforeFilters = new ListBuffer[() => Any]
  def before(fun: => Any) = beforeFilters += { () => fun }

  protected val afterFilters = new ListBuffer[() => Any]
  def after(fun: => Any) = afterFilters += { () => fun }

  protected var doNotFound: Action
  def notFound(fun: => Any) = doNotFound = { () => fun }

  protected def handleError(e: Throwable): Any = {
    status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    _caughtThrowable.withValue(e) { errorHandler() }
  }

  protected var errorHandler: Action = { () => throw caughtThrowable }
  def error(fun: => Any) = errorHandler = { () => fun }

  private val _caughtThrowable = new DynamicVariable[Throwable](null)
  protected def caughtThrowable = _caughtThrowable.value

  protected def renderResponse(actionResult: Any) {
    if (contentType == null)
      contentType = inferContentType(actionResult)
    renderResponseBody(actionResult)
  }

  protected def inferContentType(actionResult: Any): String = actionResult match {
    case _: NodeSeq => "text/html"
    case _: Array[Byte] => "application/octet-stream"
    case _ => "text/plain"
  }

  protected def renderResponseBody(actionResult: Any) {
    actionResult match {
      case bytes: Array[Byte] =>
        response.getOutputStream.write(bytes)
      case file: File =>
        using(new FileInputStream(file)) { in => copy(in, response.getOutputStream) }
      case _: Unit =>
      // If an action returns Unit, it assumes responsibility for the response
      case x: Any  =>
        response.getWriter.print(x.toString)
    }
  }

  protected[scalatra] val _multiParams = new DynamicVariable[Map[String, Seq[String]]](Map())
  protected def multiParams: MultiParams = (_multiParams.value).withDefaultValue(Seq.empty)
  /*
   * Assumes that there is never a null or empty value in multiParams.  The servlet container won't put them
   * in request.getParameters, and we shouldn't either.
   */
  protected val _params = new MultiMapHeadView[String, String] with MapWithIndifferentAccess[String] {
    protected def multiMap = multiParams
  }
  def params = _params

  def redirect(uri: String) = (_response value) sendRedirect uri
  implicit def request = _request value
  implicit def response = _response value
  def session = request.getSession
  def sessionOption = request.getSession(false) match {
    case s: HttpSession => Some(s)
    case null => None
  }
  def status(code: Int) = (_response value) setStatus code

  def halt(code: Int, msg: String) = throw new HaltException(Some(code), Some(msg))
  def halt(code: Int) = throw new HaltException(Some(code), None)
  def halt() = throw new HaltException(None, None)
  private case class HaltException(val code: Option[Int], val msg: Option[String]) extends RuntimeException

  def pass() = throw new PassException
  protected[scalatra] class PassException extends RuntimeException

  /**
   * The Scalatra DSL core methods take a list of [[org.scalatra.RouteMatcher]] and a block as
   * the action body.
   * The return value of the block is converted to a string and sent to the client as the response body.
   *
   * See [[org.scalatra.ScalatraKernel.renderResponseBody]] for the detailed behaviour and how to handle your
   * response body more explicitly, and see how different return types are handled.
   *
   * The block is executed in the context of the ScalatraKernel instance, so all the methods defined in
   * this trait are also available inside the block.
   *
   * {{{
   *   get("/") {
   *     <form action="/echo">
   *       <label>Enter your name</label>
   *       <input type="text" name="name"/>
   *     </form>
   *   }
   *
   *   post("/echo") {
   *     "hello {params('name)}!"
   *   }
   * }}}
   *
   * ScalatraKernel provides implicit transformation from boolean blocks, strings and regular expressions
   * to [[org.scalatra.RouteMatcher]], so you can write code naturally
   * {{{
   *   get("/", request.getRemoteHost == "127.0.0.1") { "Hello localhost!" }
   * }}}
   *
   */
  def get(routeMatchers: RouteMatcher*)(action: => Any) = addRoute("GET", routeMatchers, action)

  /**
   * @see [[org.scalatra.ScalatraKernel.get]]
   */
  def post(routeMatchers: RouteMatcher*)(action: => Any) = addRoute("POST", routeMatchers, action)

  /**
   * @see [[org.scalatra.ScalatraKernel.get]]
   */
  def put(routeMatchers: RouteMatcher*)(action: => Any) = addRoute("PUT", routeMatchers, action)

  /**
   * @see [[org.scalatra.ScalatraKernel.get]]
   */
  def delete(routeMatchers: RouteMatcher*)(action: => Any) = addRoute("DELETE", routeMatchers, action)

  /**
   * @see [[org.scalatra.ScalatraKernel.get]]
   */
  def options(routeMatchers: RouteMatcher*)(action: => Any) = addRoute("OPTIONS", routeMatchers, action)

  /**
   * registers a new route for the given HTTP method, can be overriden so that subtraits can use their own logic
   * for example, restricting protocol usage, namespace routes based on class name, raise errors on overlapping entries
   * etc.
   *
   * This is the method invoked by get(), post() etc.
   *
   * @see removeRoute
   */
  protected[scalatra] def addRoute(verb: String, routeMatchers: Iterable[RouteMatcher], action: => Any): Route = {
    val route = new Route(routeMatchers, () => action)
    modifyRoutes(verb, route :: _ )
    route
  }

  /**
   * removes _all_ the actions of a given route for a given HTTP method.
   * If [[addRoute]] is overriden this should probably be overriden too.
   *
   * @see addRoute
   */
  protected def removeRoute(verb: String, route: Route): Unit = {
    modifyRoutes(verb, _ filterNot (_ == route) )
    route
  }

  /**
   * since routes is a ConcurrentMap and we avoid locking, we need to retry if there are
   * concurrent modifications, this is abstracted here for removeRoute and addRoute
   */
  @tailrec private def modifyRoutes(protocol: String, f: (List[Route] => List[Route])): Unit = {
    val oldRoutes = Routes(protocol)
    if (!Routes.replace(protocol, oldRoutes, f(oldRoutes))) {
      modifyRoutes(protocol,f)
    }
  }

  private var config: Config = _
  def initialize(config: Config) = this.config = config

  def initParameter(name: String): Option[String] = config match {
    case config: ServletConfig => Option(config.getInitParameter(name))
    case config: FilterConfig => Option(config.getInitParameter(name))
    case _ => None
  }

  def environment: String = System.getProperty(EnvironmentKey, initParameter(EnvironmentKey).getOrElse("development"))
  def isDevelopmentMode = environment.toLowerCase.startsWith("dev")
}
