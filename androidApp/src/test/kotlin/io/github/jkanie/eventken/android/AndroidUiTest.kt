package io.github.jkanie.eventken.android

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.jkanie.eventken.android.ui.AndroidTags
import io.github.jkanie.eventken.android.ui.EventkenAndroidApp
import io.github.jkanie.eventken.shared.app.SearchModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real Compose screens on Robolectric, with a fake server and a fake location. No emulator needed. */
@RunWith(AndroidJUnit4::class)
class AndroidUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val server = FakeServer()
    private lateinit var graph: AppGraph
    private lateinit var search: SearchModel

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        graph = testGraph(app, server)
        search = SearchModel(graph.client, graph.store, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        compose.setContent { EventkenAndroidApp(search, graph) }
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun searchShowsAListOfResults() {
        compose.onNodeWithTag(AndroidTags.QUERY).performTextInput("concert")
        compose.onNodeWithTag(AndroidTags.SEARCH).performClick()
        waitForTag(AndroidTags.result(1))
        compose.onNodeWithText("Harbour Lights Summer Concert").assertExists()
        compose.onNodeWithText("Harbourside Hall, 100 Wharf Lane · 20 m").assertExists()
        assertEquals(
            "concert",
            server.requests
                .last { it.url.encodedPath == "/api/events" }
                .url.parameters["q"],
        )
    }

    @Test
    fun nearMeSendsTheFakeLocation() {
        compose.onNodeWithTag(AndroidTags.NEAR_ME).performClick()
        waitForTag(AndroidTags.result(1))
        val params =
            server.requests
                .last { it.url.encodedPath == "/api/events" }
                .url.parameters
        assertEquals("48.4236", params["lat"])
        assertEquals("-123.3702", params["lon"])
        assertEquals("25.0", params["radiusKm"])
        assertEquals("distance", params["sort"])
    }

    @Test
    fun saveRemindAndAddToCalendar() {
        compose.onNodeWithTag(AndroidTags.SEARCH).performClick()
        waitForTag(AndroidTags.result(1))
        compose.onNodeWithTag(AndroidTags.result(1)).performClick()
        compose.waitUntil(
            5_000,
        ) { compose.onAllNodes(hasText("Organised by Lantern Hill Arts Collective")).fetchSemanticsNodes().isNotEmpty() }

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithTag(AndroidTags.REMIND).performClick()
        compose.onNodeWithText("2 reminders set.").assertExists()
        assertTrue(graph.store.isSaved(1), "a reminder saves the event too")
        assertTrue(graph.store.get(1)!!.remindersOn)

        // The notification permission was granted before the tap, so the app asked for nothing.
        val shadow = shadowOf(compose.activity)
        assertNull(shadow.nextStartedActivity)

        // The detail screen scrolls on a small screen, so the button is brought into view first.
        compose.onNodeWithTag(AndroidTags.CALENDAR).performScrollTo().performClick()
        assertEquals(Intent.ACTION_INSERT, shadow.nextStartedActivity.action)
    }

    @Test
    fun savedEventsShowWithoutTheServer() {
        compose.onNodeWithTag(AndroidTags.SEARCH).performClick()
        waitForTag(AndroidTags.result(1))
        compose.onNodeWithTag(AndroidTags.result(1)).performClick()
        waitForTag(AndroidTags.SAVE)
        compose.onNodeWithTag(AndroidTags.SAVE).performClick()
        compose.onNodeWithText("Back").performClick()

        val before = server.requests.size
        compose.onNodeWithTag(AndroidTags.SAVED_TAB).performClick()
        waitForTag(AndroidTags.saved(1))
        assertEquals(before, server.requests.size, "the saved list reads only the device database")
    }
}
