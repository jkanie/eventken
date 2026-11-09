package io.github.jkanie.eventken.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.jkanie.eventken.android.AppGraph
import io.github.jkanie.eventken.android.calendar.CalendarIntents
import io.github.jkanie.eventken.shared.app.SearchModel
import io.github.jkanie.eventken.shared.format.EventText
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSummary
import io.github.jkanie.eventken.shared.search.SortOrder
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

object AndroidTags {
    const val QUERY = "query"
    const val SEARCH = "search"
    const val NEAR_ME = "near-me"
    const val RESULTS = "results"
    const val ERROR = "error"
    const val DETAIL = "detail"
    const val SAVE = "save"
    const val REMIND = "remind"
    const val CALENDAR = "calendar"
    const val SAVED_TAB = "tab-saved"
    const val SEARCH_TAB = "tab-search"

    fun result(id: Long) = "result-$id"

    fun saved(id: Long) = "saved-$id"
}

private val Light = lightColorScheme(primary = Color(0xFF00696E), secondary = Color(0xFF8A5100))
private val Dark = darkColorScheme(primary = Color(0xFF4FD8DE), secondary = Color(0xFFFFB870))

@Composable
fun EventkenAndroidApp(
    search: SearchModel,
    graph: AppGraph,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by search.state.collectAsState()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Text("⌕") },
                        label = { Text("Search") },
                        modifier = Modifier.testTag(AndroidTags.SEARCH_TAB),
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Text("★") },
                        label = { Text("Saved") },
                        modifier = Modifier.testTag(AndroidTags.SAVED_TAB),
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                val selected = state.selected
                when {
                    selected != null -> DetailScreen(selected, state.selectedSaved, search, graph)
                    tab == 0 -> SearchScreen(search, graph)
                    else -> SavedScreen(search)
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    model: SearchModel,
    graph: AppGraph,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var nearMe by rememberSaveable { mutableStateOf(state.latitude != null) }

    fun useLocation() {
        scope.launch {
            val point = graph.location.current()
            if (point == null) {
                nearMe = false
                model.setLocation(null, null)
                model.setRadius(null)
            } else {
                model.setLocation(point.latitude, point.longitude)
                model.setRadius(25.0)
                model.setSort(SortOrder.DISTANCE)
                model.search()
            }
        }
    }
    val askLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) useLocation() else nearMe = false
        }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = model::setText,
            label = { Text("What are you looking for?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(AndroidTags.QUERY),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::search, modifier = Modifier.testTag(AndroidTags.SEARCH)) { Text("Search") }
            FilterChip(
                selected = nearMe,
                onClick = {
                    nearMe = !nearMe
                    when {
                        !nearMe -> {
                            model.setLocation(null, null)
                            model.setRadius(null)
                            model.setSort(null)
                        }

                        graph.location.hasPermission() -> {
                            useLocation()
                        }

                        else -> {
                            askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                },
                label = { Text("Near me (25 km)") },
                modifier = Modifier.testTag(AndroidTags.NEAR_ME),
            )
        }
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(AndroidTags.ERROR)) }
        if (state.searched) Text("${state.total} events", style = MaterialTheme.typography.labelLarge)
        LazyColumn(modifier = Modifier.testTag(AndroidTags.RESULTS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.results, key = { it.id }) { event ->
                EventRow(event, Modifier.testTag(AndroidTags.result(event.id))) { model.open(event.id) }
            }
            if (state.canLoadMore) item { TextButton(onClick = model::loadMore) { Text("Show more") } }
        }
    }
}

@Composable
fun EventRow(
    event: EventSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(EventText.whenText(event, TimeZone.currentSystemDefault()), style = MaterialTheme.typography.bodyMedium)
            val extras =
                listOfNotNull(
                    EventText.placeText(event),
                    event.distanceKm?.let(EventText::distanceText),
                    if (event.cancelled) "Cancelled" else null,
                )
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SavedScreen(model: SearchModel) {
    val saved by model.saved.collectAsState(initial = emptyList())
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.isEmpty()) Text("Nothing saved yet. Saved events stay on this device and open without a connection.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(saved, key = { it.detail.summary.id }) { item ->
                val id = item.detail.summary.id
                EventRow(item.detail.summary, Modifier.testTag(AndroidTags.saved(id))) { model.open(id) }
            }
        }
    }
}

@Composable
fun DetailScreen(
    detail: EventDetail,
    saved: Boolean,
    model: SearchModel,
    graph: AppGraph,
) {
    val context = LocalContext.current
    val event = detail.summary
    var reminderText by remember(event.id) { mutableStateOf<String?>(null) }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .testTag(AndroidTags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = model::close) { Text("Back") }
        Text(event.title, style = MaterialTheme.typography.headlineSmall)
        Text(EventText.whenText(event, TimeZone.currentSystemDefault()))
        EventText.placeText(event)?.let { Text(it) }
        detail.organiser?.let { Text("Organised by $it", style = MaterialTheme.typography.bodySmall) }
        detail.description?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::toggleSaved, modifier = Modifier.testTag(AndroidTags.SAVE)) { Text(if (saved) "Saved" else "Save") }
            OutlinedButton(
                onClick = {
                    if (!saved) model.toggleSaved()
                    // Android 13 and later show notifications only with this permission, so ask for it once, when it is missing.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val plan = graph.reminders.schedule(event)
                    graph.store.setReminders(event.id, plan.isNotEmpty())
                    reminderText =
                        when (plan.size) {
                            0 -> "No reminder left to set: the event starts too soon."
                            1 -> "1 reminder set."
                            else -> "${plan.size} reminders set."
                        }
                },
                modifier = Modifier.testTag(AndroidTags.REMIND),
            ) { Text("Remind me") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(CalendarIntents.insert(detail))
                    } catch (_: ActivityNotFoundException) {
                        reminderText = "No calendar app is installed."
                    }
                },
                modifier = Modifier.testTag(AndroidTags.CALENDAR),
            ) { Text("Add to calendar") }
            event.url?.let { url ->
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }) { Text("Open page") }
            }
        }
        reminderText?.let { Text(it) }
        LaunchedEffect(event.id) { reminderText = null }
    }
}
