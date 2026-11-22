package ee.schimke.composeai.uibuilder.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/** Entry point of `compose-ui-builder-server`; see `UiBuilderServerConfig.fromEnvironment`. */
fun main() {
  val config = UiBuilderServerConfig.fromEnvironment()
  val client = upstreamClient()
  println(
    "compose-ui-builder-server ${config.version} on :${config.port}, " +
      "forwarding unmoved routes to ${config.upstream}",
  )
  embeddedServer(CIO, port = config.port, host = "0.0.0.0") { uiBuilderServer(config, client) }
    .start(wait = true)
}
