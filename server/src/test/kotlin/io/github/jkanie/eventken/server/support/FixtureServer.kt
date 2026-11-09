package io.github.jkanie.eventken.server.support

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** Serves the fixtures folder on 127.0.0.1, like ./gradlew :server:fixtureHost does, on a free port. */
class FixtureServer(
    root: File = Fixtures.dir,
) : AutoCloseable {
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val file = File(root, exchange.requestURI.path.removePrefix("/")).canonicalFile
                val inside = file.path.startsWith(root.canonicalPath) && file.isFile
                val body = if (inside) file.readBytes() else ByteArray(0)
                val type =
                    when (file.extension) {
                        "ics" -> "text/calendar; charset=utf-8"
                        "html" -> "text/html; charset=utf-8"
                        else -> "text/plain; charset=utf-8"
                    }
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(if (inside) 200 else 404, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }

    val baseUrl = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
