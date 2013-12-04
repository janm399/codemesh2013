package org.eigengo.sd.api

import akka.actor.{ Actor, ActorRef }
import spray.http._
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure
import org.eigengo.sd.core.Begin
import org.eigengo.sd.core.CoordinatorActor.{ SingleImage, FrameChunk }
import spray.can.Http
import spray.can.Http.RegisterChunkHandler

object StreamingRecogService {
  def makePattern(start: String) = (start + """(.*)""").r

  val RootUri = "/recog"
  val MJPEGUri = makePattern("/recog/mjpeg/")
  val H264Uri = makePattern("/recog/h264/")
  val RtspUri = makePattern("/recog/rtsp/")
  val StaticUri = makePattern("/recog/static/")
}

/**
 * Given the ``coordinator``, it receives messages that represent the HTTP requests;
 * processes them and routes messages into the core of our system.
 *
 * @param coordinator the coordinator that does all the heavy lifting
 */
class StreamingRecogService(coordinator: ActorRef) extends Actor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import context.dispatcher
  implicit val timeout = akka.util.Timeout(2.seconds)

  import StreamingRecogService._

  def receive = {
    case _: Http.Connected =>
      sender ! Http.Register(self)
    // POST to /recog/...
    case HttpRequest(HttpMethods.POST, uri, _, entity, _) =>
      val client = sender
      uri.path.toString() match {
        case RootUri =>
          (coordinator ? Begin(1)).mapTo[String].onComplete {
            case Success(sessionId) => client ! HttpResponse(entity = sessionId)
            case Failure(ex) => client ! HttpResponse(entity = ex.getMessage, status = StatusCodes.InternalServerError)
          }
        case StaticUri(sessionId) =>
          coordinator ! SingleImage(sessionId, entity.data.toByteArray, true)
        case RtspUri(sessionId) =>
          sender ! HttpResponse(entity = "Listening to " + entity.asString)
      }

    // stream to /recog/mjpeg/:id
    case ChunkedRequestStart(HttpRequest(HttpMethods.POST, uri, _, entity, _)) =>
      uri.path.toString() match {
        case MJPEGUri(sessionId) => coordinator ! SingleImage(sessionId, entity.data.toByteArray, false)
        case H264Uri(sessionId)  => coordinator ! FrameChunk(sessionId, entity.data.toByteArray, false)
      }
      sender ! RegisterChunkHandler(self)
    // stream to /recog/h264/:id
    case MessageChunk(data, _) =>
      // Ghetto: we say that the chunk's bytes are
      //  * 0 - 35: the session ID in ASCII encoding
      //  * 36    : the kind of chunk (H.264, JPEG, ...)
      //  * 37    : indicator whether this chunk is the end of some larger logical unit (i.e. image)

      // parse the body
      val body = data.toByteArray
      val frame = Array.ofDim[Byte](body.length - 38)
      Array.copy(body, 38, frame, 0, frame.length)

      // extract the components
      val sessionId = new String(body, 0, 36)
      val marker = body(36)
      val end = body(37) == 'E'

      // prepare the message
      val message = if (marker == 'H') FrameChunk(sessionId, frame, end) else SingleImage(sessionId, frame, end)

      // our work is done: bang it to the coordinator.
      coordinator ! message
    case ChunkedMessageEnd(_, _) =>
      // we say nothing back
      sender ! HttpResponse(entity = "{}")

    // all other requests
    case HttpRequest(method, uri, _, _, _) =>
      println(s"XXX $uri")
      sender ! HttpResponse(entity = "No such endpoint. That's all we know.", status = StatusCodes.NotFound)
  }

}
