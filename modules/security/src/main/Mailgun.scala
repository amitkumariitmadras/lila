package lila.security

import scala.concurrent.duration._

import akka.actor.ActorSystem
import play.api.libs.ws.{ WS, WSAuthScheme }
import play.api.Play.current
import play.twirl.api.Html

import lila.common.EmailAddress

final class Mailgun(
    apiUrl: String,
    apiKey: String,
    from: String,
    replyTo: String,
    system: ActorSystem
) {

  def send(msg: Mailgun.Message): Funit =
    WS.url(s"$apiUrl/messages").withAuth("api", apiKey, WSAuthScheme.BASIC).post(Map(
      "from" -> Seq(msg.from | from),
      "to" -> Seq(msg.to.value),
      "h:Reply-To" -> Seq(msg.replyTo | replyTo),
      "o:tag" -> msg.tag.toSeq,
      "subject" -> Seq(msg.subject),
      "text" -> Seq(msg.text)
    ) ++ msg.htmlBody.?? { body =>
        Map("html" -> Seq(Mailgun.html.wrap(msg.subject, body)))
      }).void addFailureEffect {
      case e: java.net.ConnectException => lila.mon.http.mailgun.timeout()
      case _ =>
    } recoverWith {
      case e if msg.retriesLeft > 0 => akka.pattern.after(15 seconds, system.scheduler) {
        send(msg.copy(retriesLeft = msg.retriesLeft - 1))
      }
      case e => fufail(e)
    }
}

object Mailgun {

  case class Message(
    to: EmailAddress,
    subject: String,
    text: String,
    htmlBody: Option[String] = none,
    from: Option[String] = none,
    replyTo: Option[String] = none,
    tag: Option[String] = none,
    retriesLeft: Int = 3
  )

  object html {

    val serviceNote = """
<div itemprop="publisher" itemscope itemtype="http://schema.org/Organization">
  <small>This is a service email related to your use of <a itemprop="url" href="https://lichess.org/"><span itemprop="name">lichess.org</span></a>.</small>
</div>
"""

    def url(u: String) = s"""
<meta itemprop="url" content="$u">
<p><a itemprop="target" href="$u">$u</a></p>
<p>(Clicking not working? Try pasting it into your browser!)</p>
"""

    private[Mailgun] def wrap(subject: String, body: String) = s"""<!doctype html>
<html>
  <head>
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
    <meta name="viewport" content="width=device-width" />
    <title>$subject</title>
  </head>
  <body>
    $body
  </body>
</html>"""
  }
}
