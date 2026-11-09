package io.github.jkanie.eventken.android

import android.app.Application
import io.github.jkanie.eventken.android.location.AndroidLocationProvider
import io.github.jkanie.eventken.android.location.LocationProvider
import io.github.jkanie.eventken.android.reminders.ReminderScheduler
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.platform.AndroidPlatform

/** Builds the app's parts once. Tests replace [graph] with fakes before the activity starts. */
class EventkenApplication : Application() {
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        if (!::graph.isInitialized) {
            graph =
                AppGraph(
                    client = AndroidPlatform.client(BuildConfig.API_URL),
                    store = SavedEventStore(AndroidPlatform.savedEventsDriver(this)),
                    location = AndroidLocationProvider(this),
                    reminders = ReminderScheduler(this),
                )
        }
    }
}

class AppGraph(
    val client: EventkenClient,
    val store: SavedEventStore,
    val location: LocationProvider,
    val reminders: ReminderScheduler,
)
