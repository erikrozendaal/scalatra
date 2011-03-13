package org.scalatra

import xml.transform.{RuleTransformer, RewriteRule}
import xml.{MetaData, Elem, Node, NodeSeq}
import java.util.Locale

/**
 * Inserts CSRF information into the body of a HTML/XHTML [[NodeSeq]] response. Two
 * meta-elements are added to the head section (if present) for use with JavaScript and
 * a hidden input field containing the CSRF token is inserted into forms with a write
 * method.
 *
 * Raw NodeSeq rendering speed is about halved when compared to sending the
 * [[NodeSeq]] directly (from 6500 responses/second to 3000 responses/second for a 5
 * kilobyte HTML response), so use form helpers when performance is really critical.
 */
trait CSRFTokenRenderer extends CSRFTokenSupport {this: ScalatraKernel with RenderPipeline =>

  render[NodeSeq] {body =>
    if (csrfApplicable(body))
      applyCsrf(body)
    else
      body
  }

  protected def csrfApplicable(body: NodeSeq): Boolean = {
    val ct = contentType.toLowerCase(Locale.ENGLISH)
    ct.startsWith("text/html") || ct.startsWith("application/xhtml+xml")
  }

  protected def applyCsrf(body: NodeSeq) = body.map(CsrfTransformer)

  /* Inspired by Ruby on Rails 3. */
  private def csrfHead = (<meta name="csrf-param" content={csrfKey}/><meta name="csrf-token" content={csrfToken}/>)

  /* Inspired by Ruby on Rails 3. */
  private def csrfForm = (<div style="margin:0;padding:0;display:inline"><input type="hidden" name={csrfKey} value={csrfToken}/></div>)

  private object CsrfTransformer extends RuleTransformer(CsrfRewriteRule)

  private object CsrfRewriteRule extends RewriteRule {
    override def transform(node: Node) = node match {
      case elem: Elem if ("form".equalsIgnoreCase(elem.label) && isWriteMethod(elem.attributes)) =>
        elem.copy(child = elem.child ++ csrfForm)
      case elem: Elem if ("head".equalsIgnoreCase(elem.label)) =>
        elem.copy(child = elem.child ++ csrfHead)
      case _ =>
        node
    }

    private def isWriteMethod(attributes: MetaData): Boolean = {
      for (attribute <- attributes if "method".equalsIgnoreCase(attribute.key)) {
        if (ScalatraKernel.writeMethods.contains(attribute.value.text.toUpperCase(Locale.ENGLISH)))
          return true
      }
      return false
    }
  }
}
