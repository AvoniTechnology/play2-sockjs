package play.sockjs.core

import scala.util.control.Exception._
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.pattern.ask

import play.core.parsers.FormUrlEncodedParser
import play.api.mvc._
import play.api.mvc.BodyParsers._
import play.api.mvc.Results._
import play.api.libs.json._
import play.api.libs.iteratee._

import play.sockjs.api._

/**
 * SockJS transport helper
 */
case class Transport(f: ActorRef => (String, SockJSSettings) => SockJSHandler) {

  def apply(sessionID: String)(implicit sessionMaster: ActorRef, settings: SockJSSettings) = {
    f(sessionMaster)(sessionID, settings)
  }

}

object Transport {

  implicit val ec = play.core.Execution.internalContext
  implicit val defaultTimeout = akka.util.Timeout(5 seconds) //TODO: make it configurable?

  val P = """/([^/.]+)/([^/.]+)/([^/.]+)""".r
  def unapply(path: String): Option[(String, String)] = path match {
    case P(serverID, sessionID, transport) => Some(sessionID -> transport)
    case _ => None
  }

  /**
   * The session the client is bound to
   */
  trait Session {

    /**
     * Bind this session to the SessionMaster. The enumerator provided must be used
     * to write messages to the client
     */
    def bind(f: (Enumerator[Frame], Boolean) => Result): Result

  }

  def Send(ok: RequestHeader => Result, ko: => Result) = Transport { sessionMaster => (sessionID, settings) =>
    import settings._
    SockJSAction(Action(parse.tolerantText) { implicit req =>
      def parsePlainText(txt: String) = {
        (allCatch either Json.parse(txt)).left.map(_ => "Broken JSON encoding.")
      }
      def parseFormUrlEncoded(data: String) = {
        val query = FormUrlEncodedParser.parse(data, req.charset.getOrElse("utf-8"))
        for {
          d <- query.get("d").flatMap(_.headOption.filter(!_.isEmpty)).toRight("Payload expected.").right
          json <- parsePlainText(d).right
        } yield json
      }
      ((req.contentType.getOrElse(""), req.body) match {
        case ("application/x-www-form-urlencoded", data) => parseFormUrlEncoded(data)
        case (_, txt) if !txt.isEmpty => parsePlainText(txt)
        case _ => Left("Payload expected.")
      }).fold(
        error => InternalServerError(error),
        json => json.validate[Seq[String]].fold(
          invalid => InternalServerError("Payload expected."),
          payload => Async {
            (sessionMaster ? SessionMaster.Send(sessionID, payload)).map {
              case SessionMaster.Ack => ok(req).withCookies(cookies.map(f => List(f(req))).getOrElse(Nil):_*)
              case SessionMaster.Error => ko
            }
          }))
    })
  }

  /**
   * HTTP polling transport
   */
  def Polling = Http(Some(1)) _

  /**
   * HTTP streaming transport
   */
  def Streaming = Http(None) _

  /**
   * HTTP transport that emulate websockets. Provides method to bind this
   * transport session to the SessionMaster.
   */
  def Http(quota: Option[Long])(f: (RequestHeader, Session) => Result) = Transport { sessionMaster => (sessionID, settings) =>
    import settings._
    SockJSTransport { sockjs =>
      Action { req =>
        f(req, new Session {
          def bind(f: (Enumerator[Frame], Boolean) => Result): Result = Async {
            (sessionMaster ? SessionMaster.Get(sessionID)).map {
              case SessionMaster.SessionOpened(session) => session.bind(req, sockjs)
              case SessionMaster.SessionResumed(session) => session
            }.flatMap(_.connect(heartbeat, sessionTimeout, quota.getOrElse(streamingQuota)).map {
              case Session.Connected(enumerator, error) =>
                f(enumerator, error).withCookies(cookies.map(f => List(f(req))).getOrElse(Nil):_*)
            })
          }
        })
      }
    }
  }

}
