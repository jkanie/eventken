package io.github.jkanie.eventken.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import io.github.jkanie.eventken.shared.app.OrganiserModel
import io.github.jkanie.eventken.shared.app.SearchModel

object Tags {
    const val SEARCH_TAB = "tab-search"
    const val SAVED_TAB = "tab-saved"
    const val ORGANISER_TAB = "tab-organiser"
    const val QUERY = "query"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val RADIUS = "radius"
    const val SEARCH_BUTTON = "search"
    const val RESULTS = "results"
    const val ERROR = "error"
    const val DETAIL = "detail"
    const val SAVE_BUTTON = "save"
    const val SAVED_LIST = "saved-list"
    const val TOKEN = "token"
    const val FEED_URL = "feed-url"
    const val FEED_CATEGORY = "feed-category"
    const val ADD_FEED = "add-feed"
    const val LOAD_FEEDS = "load-feeds"
    const val INGEST = "ingest"
    const val FEEDS = "feeds"
    const val MESSAGE = "message"

    fun result(id: Long) = "result-$id"

    fun saved(id: Long) = "saved-$id"

    fun feed(id: Long) = "feed-$id"
}

private val Teal = Color(0xFF00696E)
private val Amber = Color(0xFF8A5100)

private val LightColours = lightColorScheme(primary = Teal, secondary = Amber)
private val DarkColours = darkColorScheme(primary = Color(0xFF4FD8DE), secondary = Color(0xFFFFB870))

@Composable
fun EventkenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours, content = content)
}

@Composable
fun EventkenApp(
    search: SearchModel,
    organiser: OrganiserModel,
    apiUrl: String,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    EventkenTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Search") }, modifier = Modifier.testTag(Tags.SEARCH_TAB))
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Saved") }, modifier = Modifier.testTag(Tags.SAVED_TAB))
                    Tab(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        text = { Text("Organiser") },
                        modifier = Modifier.testTag(Tags.ORGANISER_TAB),
                    )
                }
                when (tab) {
                    0 -> SearchScreen(search)
                    1 -> SavedScreen(search)
                    else -> OrganiserScreen(organiser, apiUrl)
                }
            }
        }
    }
}
