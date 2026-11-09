package io.github.jkanie.eventken.server.ingest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.Instant

/**
 * 1 event as 1 feed describes it, before merging. It holds no attendee or contact
 * data: the parsers keep only the organiser's display name.
 */
@Serializable
data class ParsedEvent(
    val externalId: String,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant? = null,
    val allDay: Boolean = false,
    val timeZone: String? = null,
    val description: String? = null,
    val venue: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val categories: List<String> = emptyList(),
    val url: String? = null,
    val organiser: String? = null,
    val cancelled: Boolean = false,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Changes when any field changes, so an unchanged event costs no write. */
    fun contentHash(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toJson().toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val json = Json { encodeDefaults = false }

        fun fromJson(text: String): ParsedEvent = Json.decodeFromString(serializer(), text)

        /** An identifier for events whose feed gives none: the same title and start give the same id. */
        fun fallbackId(
            title: String,
            startsAt: Instant,
        ): String =
            "generated:" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("$title|$startsAt".toByteArray())
                    .take(12)
                    .joinToString("") { "%02x".format(it) }
    }
}

class FeedParseException(
    message: String,
) : Exception(message)

internal fun String?.clean(maxLength: Int = 10_000): String? =
    this
        ?.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
        ?.trim()
        ?.take(maxLength)
        ?.ifEmpty { null }
