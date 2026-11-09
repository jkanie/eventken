package io.github.jkanie.eventken.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jkanie.eventken.shared.app.SearchModel
import io.github.jkanie.eventken.shared.format.EventText
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSummary
import kotlinx.datetime.TimeZone

@Composable
fun SearchScreen(model: SearchModel) {
    val state by model.state.collectAsState()
    LaunchedEffect(Unit) { model.loadCategories() }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = model::setText,
                    label = { Text("What are you looking for?") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag(Tags.QUERY),
                )
                Button(onClick = model::search, modifier = Modifier.testTag(Tags.SEARCH_BUTTON)) { Text("Search") }
            }
            LocationRow(model, state.latitude, state.longitude, state.radiusKm)
            if (state.availableCategories.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableCategories.forEach { category ->
                        FilterChip(
                            selected = category in state.categories,
                            onClick = { model.toggleCategory(category) },
                            label = { Text(category) },
                        )
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.ERROR)) }
            if (state.searched) {
                Text("${state.total} events", style = MaterialTheme.typography.labelLarge)
            }
            LazyColumn(modifier = Modifier.weight(1f).testTag(Tags.RESULTS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.results, key = { it.id }) { event ->
                    EventCard(event, selected = state.selected?.summary?.id == event.id) { model.open(event.id) }
                }
                if (state.canLoadMore) {
                    item {
                        TextButton(onClick = model::loadMore) { Text("Show more") }
                    }
                }
            }
        }
        state.selected?.let { detail ->
            DetailPane(detail, saved = state.selectedSaved, onSave = model::toggleSaved, onClose = model::close)
        }
    }
}

@Composable
private fun LocationRow(
    model: SearchModel,
    latitude: Double?,
    longitude: Double?,
    radiusKm: Double?,
) {
    // Text fields keep what the user typed, even half-typed numbers such as "48.".
    var lat by remember { mutableStateOf(latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(longitude?.toString().orEmpty()) }
    var radius by remember { mutableStateOf(radiusKm?.toString().orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = lat,
            onValueChange = {
                lat = it
                model.setLocation(it.toDoubleOrNull(), lon.toDoubleOrNull())
            },
            label = { Text("Latitude") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(Tags.LATITUDE),
        )
        OutlinedTextField(
            value = lon,
            onValueChange = {
                lon = it
                model.setLocation(lat.toDoubleOrNull(), it.toDoubleOrNull())
            },
            label = { Text("Longitude") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(Tags.LONGITUDE),
        )
        OutlinedTextField(
            value = radius,
            onValueChange = {
                radius = it
                model.setRadius(it.toDoubleOrNull())
            },
            label = { Text("Within km") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(Tags.RADIUS),
        )
    }
}

@Composable
fun EventCard(
    event: EventSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(Tags.result(event.id)),
        colors =
            if (selected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(EventText.whenText(event, TimeZone.currentSystemDefault()), style = MaterialTheme.typography.bodyMedium)
            EventText.placeText(event)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                event.distanceKm?.let { AssistChip(onClick = onClick, label = { Text(EventText.distanceText(it)) }) }
                if (event.sourceCount > 1) AssistChip(onClick = onClick, label = { Text("${event.sourceCount} sources") })
                if (event.cancelled) AssistChip(onClick = onClick, label = { Text("Cancelled") })
            }
        }
    }
}

@Composable
fun DetailPane(
    detail: EventDetail,
    saved: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val event = detail.summary
    Column(
        modifier =
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .testTag(Tags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(event.title, style = MaterialTheme.typography.headlineSmall)
        Text(EventText.whenText(event, TimeZone.currentSystemDefault()))
        EventText.placeText(event)?.let { Text(it) }
        detail.organiser?.let { Text("Organised by $it", style = MaterialTheme.typography.bodySmall) }
        if (event.categories.isNotEmpty()) Text(event.categories.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium)
        detail.description?.let { Text(it) }
        if (detail.sources.isNotEmpty()) {
            Text("Listed by", style = MaterialTheme.typography.titleSmall)
            detail.sources.forEach { Text(it.feedUrl, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, modifier = Modifier.testTag(Tags.SAVE_BUTTON)) { Text(if (saved) "Saved" else "Save") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
}
