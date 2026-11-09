package io.github.jkanie.eventken.shared.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.cache.EventkenDatabase
import io.ktor.client.engine.okhttp.OkHttp

object AndroidPlatform {
    /** SQLiteOpenHelper creates the schema on first open, through the driver. */
    fun savedEventsDriver(
        context: Context,
        name: String? = "saved-events.db",
    ): SqlDriver = AndroidSqliteDriver(EventkenDatabase.Schema, context, name)

    fun client(baseUrl: String): EventkenClient = EventkenClient(baseUrl, OkHttp.create())
}
