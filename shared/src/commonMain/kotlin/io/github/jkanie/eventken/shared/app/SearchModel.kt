package io.github.jkanie.eventken.shared.app

import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.cache.SavedEvent
import io.github.jkanie.eventken.shared.cache.SavedEventStore
import io.github.jkanie.eventken.shared.model.EventDetail
import io.github.jkanie.eventken.shared.model.EventSummary
import io.github.jkanie.eventken.shared.search.InvalidSearchQuery
import io.github.jkanie.eventken.shared.search.SearchQuery
import io.github.jkanie.eventken.shared.search.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val text: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusKm: Double? = null,
    val categories: Set<String> = emptySet(),
    val availableCategories: List<String> = emptyList(),
    val sort: SortOrder? = null,
    val results: List<EventSummary> = emptyList(),
    val total: Long = 0,
    val searched: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: EventDetail? = null,
    val selectedSaved: Boolean = false,
) {
    val canLoadMore: Boolean get() = results.size < total
}

/**
 * The search screen's state and actions, shared by the Android and desktop apps.
 * It holds no UI code, so its tests run on the JVM without a screen.
 */
class SearchModel(
    private val client: EventkenClient,
    private val store: SavedEventStore,
    private val scope: CoroutineScope,
    private val pageSize: Int = 20,
) {
    private val mutable = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutable.asStateFlow()
    private var lastQuery: SearchQuery? = null
    private var running: Job? = null

    val saved: Flow<List<SavedEvent>> get() = store.observeAll()

    fun setText(value: String) = mutable.update { it.copy(text = value) }

    fun setLocation(
        latitude: Double?,
        longitude: Double?,
    ) = mutable.update { it.copy(latitude = latitude, longitude = longitude) }

    fun setRadius(km: Double?) = mutable.update { it.copy(radiusKm = km) }

    fun setSort(order: SortOrder?) = mutable.update { it.copy(sort = order) }

    fun toggleCategory(name: String) =
        mutable.update { it.copy(categories = if (name in it.categories) it.categories - name else it.categories + name) }

    fun loadCategories() {
        scope.launch {
            runCatching { client.categories() }
                .onSuccess { list -> mutable.update { it.copy(availableCategories = list.map { c -> c.category }) } }
        }
    }

    /** Builds the query from the state. Problems show as an error, and nothing is sent. */
    fun search() {
        val s = state.value
        val query =
            try {
                SearchQuery
                    .builder()
                    .text(s.text)
                    .apply {
                        if (s.latitude != null && s.longitude != null) near(s.latitude, s.longitude, s.radiusKm)
                        s.categories.sorted().forEach(::category)
                        if (s.latitude == null &&
                            s.radiusKm != null
                        ) {
                            throw InvalidSearchQuery(listOf("Set a location to search by distance."))
                        }
                    }.sort(s.sort)
                    .page(pageSize)
                    .build()
            } catch (e: InvalidSearchQuery) {
                mutable.update { it.copy(error = e.problems.joinToString(" ")) }
                return
            }
        run(query, append = false)
    }

    fun loadMore() {
        val query = lastQuery ?: return
        if (!state.value.canLoadMore || state.value.loading) return
        run(query.nextPage(), append = true)
    }

    private fun run(
        query: SearchQuery,
        append: Boolean,
    ) {
        running?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        running =
            scope.launch {
                try {
                    val page = client.search(query)
                    lastQuery = query
                    mutable.update {
                        it.copy(
                            results = if (append) it.results + page.items else page.items,
                            total = page.total,
                            searched = true,
                            loading = false,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(loading = false, error = "Search failed: ${e.message}") }
                }
            }
    }

    /** Opens 1 event. A saved event opens from the device first, so it works offline. */
    fun open(id: Long) {
        store.get(id)?.let { saved -> mutable.update { it.copy(selected = saved.detail, selectedSaved = true) } }
        scope.launch {
            try {
                val detail = client.event(id)
                if (store.isSaved(id)) store.save(detail)
                mutable.update { it.copy(selected = detail, selectedSaved = store.isSaved(id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (state.value.selected
                        ?.summary
                        ?.id != id
                ) {
                    mutable.update { it.copy(error = "Could not open the event: ${e.message}") }
                }
            }
        }
    }

    fun close() = mutable.update { it.copy(selected = null, selectedSaved = false) }

    fun toggleSaved() {
        val detail = state.value.selected ?: return
        val id = detail.summary.id
        if (store.isSaved(id)) store.remove(id) else store.save(detail)
        mutable.update { it.copy(selectedSaved = store.isSaved(id)) }
    }
}
