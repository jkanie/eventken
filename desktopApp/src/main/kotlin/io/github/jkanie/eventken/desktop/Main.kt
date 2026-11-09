package io.github.jkanie.eventken.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.jkanie.eventken.shared.app.OrganiserModel
import io.github.jkanie.eventken.shared.app.SearchModel
import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.platform.DesktopPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import java.io.File

/**
 * The desktop app. EVENTKEN_API_URL names the server (default http://localhost:8080).
 * Saved events live in ~/.eventken/saved-events.db.
 */
fun main() {
    val apiUrl = System.getenv("EVENTKEN_API_URL") ?: "http://localhost:8080"
    val client = DesktopPlatform.client(apiUrl)
    val store = SavedEventStore(DesktopPlatform.savedEventsDriver(File(System.getProperty("user.home"), ".eventken/saved-events.db")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val search = SearchModel(client, store, scope)
    val organiser = OrganiserModel(client, scope)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "eventken",
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            EventkenApp(search, organiser, apiUrl)
        }
    }
}
