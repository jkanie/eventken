package io.github.jkanie.eventken.server.dev

import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import java.io.File

/**
 * Serves the fixtures folder over HTTP on 127.0.0.1, with its robots.txt, so the
 * server can fetch the sample feeds the way it fetches real ones. The server
 * refuses loopback addresses unless ALLOW_PRIVATE_TARGETS is on.
 */
fun main(args: Array<String>) {
    val folder = File(args.getOrNull(0) ?: "fixtures")
    val port = args.getOrNull(1)?.toInt() ?: 8089
    require(folder.isDirectory) { "No fixtures folder at ${folder.absolutePath}." }
    println("Serving ${folder.absolutePath} on http://127.0.0.1:$port/")
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        routing { staticFiles("/", folder) }
    }.start(wait = true)
}
