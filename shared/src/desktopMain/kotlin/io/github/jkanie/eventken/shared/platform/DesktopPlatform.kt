package io.github.jkanie.eventken.shared.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

object DesktopPlatform {
    /** A SQLite file for saved events. The folder is created when missing. */
    fun savedEventsDriver(file: File): SqlDriver {
        file.absoluteFile.parentFile?.mkdirs()
        return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    }

    /** An in-memory database, for tests and for a run that must leave no file behind. */
    fun inMemoryDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    fun client(baseUrl: String): EventkenClient = EventkenClient(baseUrl, OkHttp.create())
}
