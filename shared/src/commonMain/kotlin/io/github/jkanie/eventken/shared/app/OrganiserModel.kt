package io.github.jkanie.eventken.shared.app

import io.github.jkanie.eventken.shared.api.EventkenApiException
import io.github.jkanie.eventken.shared.api.EventkenClient
import io.github.jkanie.eventken.shared.model.Feed
import io.github.jkanie.eventken.shared.model.FeedKind
import io.github.jkanie.eventken.shared.model.NewFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrganiserState(
    val token: String = "",
    val feeds: List<Feed> = emptyList(),
    val newUrl: String = "",
    val newKind: FeedKind = FeedKind.ICAL,
    val newCategory: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * The organiser screen: feeds and the admin token. The token stays in memory
 * only, so closing the app forgets it and nothing on disk can leak it.
 */
class OrganiserModel(
    private val client: EventkenClient,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(OrganiserState())
    val state: StateFlow<OrganiserState> = mutable.asStateFlow()

    fun setToken(value: String) = mutable.update { it.copy(token = value.trim()) }

    fun setNewUrl(value: String) = mutable.update { it.copy(newUrl = value) }

    fun setNewKind(value: FeedKind) = mutable.update { it.copy(newKind = value) }

    fun setNewCategory(value: String) = mutable.update { it.copy(newCategory = value) }

    fun refresh() = act { token -> mutable.update { it.copy(feeds = client.feeds(token), message = null) } }

    fun add() =
        act { token ->
            val s = state.value
            val feed = client.addFeed(token, NewFeed(s.newUrl.trim(), s.newKind, s.newCategory.trim().ifEmpty { null }))
            mutable.update { it.copy(feeds = it.feeds + feed, newUrl = "", newCategory = "", message = "Added ${feed.url}.") }
        }

    fun delete(id: Long) =
        act { token ->
            client.deleteFeed(token, id)
            mutable.update { it.copy(feeds = it.feeds.filterNot { f -> f.id == id }, message = "Feed removed.") }
        }

    fun ingestNow() =
        act { token ->
            val report = client.ingestNow(token)
            val ok = report.feeds.count { it.status.startsWith("ok") || it.status == "not modified" }
            val created = report.feeds.sumOf { it.created }
            val feeds = client.feeds(token)
            mutable.update {
                it.copy(feeds = feeds, message = "Fetched ${report.feeds.size} feeds: $ok without errors, $created new events.")
            }
        }

    private fun act(block: suspend (String) -> Unit) {
        val token = state.value.token
        if (token.isEmpty()) {
            mutable.update { it.copy(message = "Enter the admin token first.") }
            return
        }
        mutable.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                block(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EventkenApiException) {
                mutable.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                mutable.update { it.copy(message = "The server could not be reached: ${e.message}") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
