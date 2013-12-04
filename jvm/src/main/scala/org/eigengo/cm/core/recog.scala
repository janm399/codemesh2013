package org.eigengo.cm.core

import akka.actor._
import scala.concurrent.{ ExecutionContext, Future }
import com.rabbitmq.client.AMQP
import com.github.sstone.amqp.{ ConnectionOwner, RpcClient }
import akka.util.Timeout
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.RpcClient.Response
import scala.Some
import com.github.sstone.amqp.RpcClient.Request
import com.github.sstone.amqp.Amqp.Delivery
import spray.json.{ JsonParser, JsonReader, DefaultJsonProtocol }

private[core] object RecogSessionActor {

  // receive image to be processed
  private[core] case class Image(image: Array[Byte]) extends AnyVal
  // receive chunk of a frame to be processed
  private[core] case class Frame(frameChunk: Array[Byte]) extends AnyVal

  // FSM states
  private[core] sealed trait State
  private[core] case object Idle extends State
  private[core] case object Completed extends State
  private[core] case object Aborted extends State
  private[core] case object Active extends State

  // FSM data
  private[core] sealed trait Data
  private[core] case object Empty extends Data
  private[core] case class Starting(minCoins: Int) extends Data
  private[core] case class Running(decoder: DecoderContext) extends Data

  // CV responses
  private[core] case class Point(x: Int, y: Int)
  private[core] case class Coin(center: Point, radius: Double)
  private[core] case class CoinResponse(coins: List[Coin], succeeded: Boolean)

}

/**
 * We define the instances of ``JsonReader`` for various types in a trait, because
 * we may decide to use them outside of the core; I can imagine a situation where
 * we would like to use them in our REST API.
 *
 * In that case, all that we'd have to do is to mix in this trait to our Spray endpoint
 * and then happily use ``complete`` or ``ctx.complete``.
 */
trait RecogSessionActorFormats extends DefaultJsonProtocol {
  import RecogSessionActor._

  implicit val PointFormat = jsonFormat2(Point)
  implicit val CoinFormat = jsonFormat2(Coin)
  implicit val CoinResponseFormat = jsonFormat2(CoinResponse)
}

/**
 * This actor deals with the states of the recognition session. We use FSM here--even though
 * we only have a handful of states, recognising things sometimes needs many more states
 * and using ``become`` and ``unbecome`` of the ``akka.actor.ActorDSL._`` would be cumbersome.
 *
 * @param amqpConnection the AMQP connection we will use to create individual 'channels'
 * @param jabberActor the actor that will receive our output
 */
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with FSM[RecogSessionActor.State, RecogSessionActor.Data] with AmqpOperations with RecogSessionActorFormats with ImageEncoding {

  import RecogSessionActor._
  import CoordinatorActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

  // make a connection to the AMQP broker
  val amqp = ConnectionOwner.createChildActor(amqpConnection, Props(new RpcClient()))

  startWith(Idle, Empty)
  // when we receive the ``Begin`` even when idle, we become ``Active``
  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! self.path.name
      goto(Active) using Starting(minCoins)
  }

  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(Image(image), Starting(minCoins)) =>
      // Image with no decoder yet. We will be needing the ChunkingDecoderContext.
      val decoder = new ChunkingDecoderContext(countCoins(minCoins))
      decoder.decode(image, false)
      stay() using Running(decoder)
    case Event(Image(image), Running(decoder)) if image.length > 0 =>
      // Image with existing decoder. Shut up and apply.
      decoder.decode(image, false)
      stay()
    case Event(Image(_), Running(decoder)) =>
      // Empty image (following the previous case)
      decoder.close()
      goto(Completed)

    case Event(Frame(frame), Starting(minCoins)) =>
      // Frame with no decoder yet. We will be needing the H264DecoderContext.
      val decoder = new H264DecoderContext(countCoins(minCoins))
      decoder.decode(frame, true)
      stay() using Running(decoder)
    case Event(Frame(frame), Running(decoder)) if frame.length > 0 =>
      // Frame with existing decoder. Just decode. (Teehee--I said ``Just``.)
      decoder.decode(frame, true)
      stay()
    case Event(Frame(_), Running(decoder)) =>
      // Last frame
      decoder.close()
      goto(Completed)
  }

  // until we hit Aborted and Completed, which do nothing interesting
  when(Aborted)(emptyBehaviour)
  when(Completed)(emptyBehaviour)

  // unhandled events in the states
  whenUnhandled {
    case Event(StateTimeout, _) => goto(Aborted)
  }

  // cleanup
  onTransition {
    case _ -> Aborted => log.info("Aborting!"); context.stop(self)
    case _ -> Completed => log.info("Done!"); context.stop(self)
  }

  // go!
  initialize()

  // cleanup
  override def postStop() {
    context.stop(amqp)
  }

  // Curried function that--when applied to the first parameter list--
  // is nicely suitable for the various ``DecoderContext``s
  def countCoins(minCoins: Int)(image: Array[Byte]): Unit =
    amqpAsk[CoinResponse](amqp)("cm.exchange", "cm.coins.key", mkImagePayload(image)) onSuccess {
      case res => if (res.coins.size >= minCoins) jabberActor ! res
    }
}

/**
 * Contains useful functions (actually just one here) for the AMQP business.
 * Deals with JSON serialization.
 */
private[core] trait AmqpOperations {

  def amqpAsk[A](amqp: ActorRef)(exchange: String, routingKey: String, payload: Array[Byte])(implicit ctx: ExecutionContext, reader: JsonReader[A]): Future[A] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload) :: Nil)).map {
      case Response(Delivery(_, _, _, body) :: _) =>
        val s = new String(body)
        reader.read(JsonParser(s))
    }
  }

}
