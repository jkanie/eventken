package io.github.jkanie.eventken.server

import io.github.jkanie.eventken.server.db.Db
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Reads the settings from the environment, migrates the database, then serves the API. */
fun main() {
    val config = ServerConfig.fromEnvironment()
    val dataSource = Db.pool(config)
    Db.migrate(dataSource)
    val services = Services.create(config, dataSource)
    embeddedServer(Netty, port = config.port, host = config.host) {
        eventken(services, scheduleIngest = true)
    }.start(wait = true)
}
