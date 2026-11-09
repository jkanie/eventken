package io.github.jkanie.eventken.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.github.jkanie.eventken.android.ui.EventkenAndroidApp
import io.github.jkanie.eventken.shared.app.SearchModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as EventkenApplication).graph
        val search = SearchModel(graph.client, graph.store, lifecycleScope)
        setContent { EventkenAndroidApp(search, graph) }
    }
}
