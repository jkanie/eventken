package io.github.jkanie.eventken.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jkanie.eventken.shared.app.SearchModel

/** Saved events come from the local database, so this screen works without the server. */
@Composable
fun SavedScreen(model: SearchModel) {
    val saved by model.saved.collectAsState(initial = emptyList())
    val state by model.state.collectAsState()
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved.isEmpty()) {
                Text("Nothing saved yet. Open an event and press Save to keep it here, also offline.")
            }
            LazyColumn(modifier = Modifier.testTag(Tags.SAVED_LIST), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(saved, key = { it.detail.summary.id }) { item ->
                    Column(modifier = Modifier.testTag(Tags.saved(item.detail.summary.id))) {
                        EventCard(item.detail.summary, selected = state.selected?.summary?.id == item.detail.summary.id) {
                            model.open(item.detail.summary.id)
                        }
                    }
                }
            }
            if (saved.isNotEmpty()) Text("${saved.size} saved", style = MaterialTheme.typography.labelLarge)
        }
        state.selected?.let { detail ->
            DetailPane(detail, saved = state.selectedSaved, onSave = model::toggleSaved, onClose = model::close)
        }
    }
}
