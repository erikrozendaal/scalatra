package org.scalatra

import test.specs.ScalatraSpecification
import xml.XML


class CSRFTokenRendererServlet extends ScalatraServlet with RenderPipeline with CSRFTokenRenderer {
  get("/form") {
    <html>
      <head>
        <title>Form</title> <meta name="foo" value="bar"/>
      </head>
      <body>
        <form method="POST">
            <input type="text"/>
        </form>
      </body>
    </html>
  }

  get("/form-without-method") {
    <html>
      <head/>
      <body>
        <form>
            <input type="text" name="q"/>
        </form>
      </body>
    </html>
  }

  get("/form-with-get-method") {
    <html>
      <head/>
      <body>
        <form METHOD="Get">
            <input type="text" name="q"/>
        </form>
      </body>
    </html>
  }

  get("/text") {
    "<form method=\"post\"></form>"
  }
}

object CSRFTokenRendererSpec extends ScalatraSpecification {
  addServlet(classOf[CSRFTokenRendererServlet], "/*")

  "the csrf-param and csrf-value meta fields should be added to head" in {
    get("/form") {
      body must beMatching("""<meta content="csrfToken" name="csrf-param">""")
      body must beMatching("""<meta content="(\w+)" name="csrf-token">""")
    }
  }

  "the csrf-token should be added as hidden input to forms with post method" in {
    get("/form") {
      val token = ("content=\"(\\w+)\" name=\"csrf-token\"".r findFirstMatchIn body).get.subgroups.head
      body must beMatching("""<div style="margin:0;padding:0;display:inline">""")
      body must beMatching("""<input value="""" + token + """" type="hidden" name="csrfToken">""")
    }
  }

  "the csrf-token should not be added to forms without explicit method" in {
    get("/form-without-method") {
      val token = ("content=\"(\\w+)\" name=\"csrf-token\"".r findFirstMatchIn body).get.subgroups.head
      body must notBeMatching("""<input value="""" + token + """" type="hidden" name="csrfToken">""")
    }
  }

  "the csrf-token should not be added to forms with the get method" in {
    get("/form-with-get-method") {
      val token = ("content=\"(\\w+)\" name=\"csrf-token\"".r findFirstMatchIn body).get.subgroups.head
      body must notBeMatching("""<input value="""" + token + """" type="hidden" name="csrfToken">""")
    }
  }

  "should not apply to strings" in {
    get("/text") {
      body must beEqualTo("<form method=\"post\"></form>")
    }
  }
}
