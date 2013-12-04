package org.eigengo.sd.api

import org.eigengo.sd.core.Core
import akka.actor.Props
import spray.can.Http
import akka.io.IO

/**
 * The REST API server. Uses Spray-can and Spray-can's chunked HTTP processing (see
 * the ``spray.can`` secion of the ``application.conf``).
 *
 * Apart from that, it requires the functionality of ``Core`` that it wraps in REST
 * API.
 */
trait Api {
  this: Core =>

  // our endpoints
  val streamingRecogService = system.actorOf(Props(new StreamingRecogService(coordinator)))

  private val io = IO(Http)(system)
  io ! Http.Bind(streamingRecogService, "localhost", port = 8080)

}
