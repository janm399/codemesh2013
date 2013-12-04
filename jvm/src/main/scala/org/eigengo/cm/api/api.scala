package org.eigengo.cm.api

import org.eigengo.cm.core.Core
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
  val recogService = system.actorOf(Props(new RecogService(coordinator)))

  IO(Http)(system) ! Http.Bind(recogService, "0.0.0.0", port = 8080)

}
