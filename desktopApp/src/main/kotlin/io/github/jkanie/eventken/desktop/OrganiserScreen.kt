package io.github.jkanie.eventken.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jkanie.eventken.shared.app.OrganiserModel
import io.github.jkanie.eventken.shared.model.FeedKind

@Composable
fun OrganiserScreen(
    model: OrganiserModel,
    apiUrl: String,
) {
    val state by model.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Feeds on $apiUrl", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.token,
                onValueChange = model::setToken,
                label = { Text("Admin token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f).testTag(Tags.TOKEN),
            )
            OutlinedButton(onClick = model::refresh, modifier = Modifier.testTag(Tags.LOAD_FEEDS)) { Text("Load feeds") }
            Button(onClick = model::ingestNow, modifier = Modifier.testTag(Tags.INGEST)) { Text("Fetch all now") }
        }

        Text("Add a feed", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.newUrl,
                onValueChange = model::setNewUrl,
                label = { Text("Feed URL (.ics file or page with JSON-LD)") },
                singleLine = true,
                modifier = Modifier.weight(2f).testTag(Tags.FEED_URL),
            )
            OutlinedTextField(
                value = state.newCategory,
                onValueChange = model::setNewCategory,
                label = { Text("Category for every event (optional)") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(Tags.FEED_CATEGORY),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FeedKind.entries.forEach { kind ->
                FilterChip(
                    selected = state.newKind == kind,
                    onClick = { model.setNewKind(kind) },
                    label = { Text(if (kind == FeedKind.ICAL) "iCalendar" else "JSON-LD page") },
                )
            }
            Button(
                onClick = model::add,
                enabled = state.newUrl.isNotBlank(),
                modifier = Modifier.testTag(Tags.ADD_FEED),
            ) { Text("Add feed") }
        }

        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, modifier = Modifier.testTag(Tags.MESSAGE)) }

        LazyColumn(modifier = Modifier.testTag(Tags.FEEDS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.feeds, key = { it.id }) { feed ->
                Card(modifier = Modifier.fillMaxWidth().testTag(Tags.feed(feed.id))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(feed.url, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${feed.kind} · ${feed.eventCount} events · ${feed.lastStatus ?: "not fetched yet"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { model.delete(feed.id) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
