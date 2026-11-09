package io.github.jkanie.eventken.server.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Reads schema.org Event objects from the JSON-LD script tags of a web page.
 * Many venue and ticket sites publish these for search engines, so they are a
 * common, structured source that needs no scraping of the page layout.
 */
class JsonLdParser(
    private val defaultZone: ZoneId = ZoneOffset.UTC,
) {
    private val json = Json { isLenient = true }

    fun parse(
        html: String,
        pageUrl: String,
    ): List<ParsedEvent> {
        val document = Jsoup.parse(html, pageUrl)
        val nodes =
            document.select("script[type=application/ld+json]").flatMap { script ->
                val element = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: return@flatMap emptyList()
                flatten(element)
            }
        return nodes.mapNotNull { toEvent(it, pageUrl) }.distinctBy { it.externalId }
    }

    /** Collects every object, looking into arrays and @graph lists. */
    private fun flatten(element: JsonElement): List<JsonObject> =
        when (element) {
            is JsonArray -> {
                element.flatMap(::flatten)
            }

            is JsonObject -> {
                val graph = element["@graph"]
                if (graph != null) flatten(graph) else listOf(element)
            }

            else -> {
                emptyList()
            }
        }

    private fun toEvent(
        node: JsonObject,
        pageUrl: String,
    ): ParsedEvent? {
        val types = node["@type"].strings()
        val eventType = types.firstOrNull { it in EVENT_TYPES || it.endsWith("Event") } ?: return null
        val title = node["name"].text().clean(500) ?: return null
        val start = node["startDate"].text()?.let(::parseDate) ?: return null
        val end = node["endDate"].text()?.let(::parseDate)

        val place = node["location"].objects().firstOrNull { it["@type"].strings().none { t -> t == "VirtualLocation" } }
        val online =
            node["location"].objects().any { it["@type"].strings().contains("VirtualLocation") } ||
                node["eventAttendanceMode"].text()?.endsWith("OnlineEventAttendanceMode") == true
        val geo = place?.get("geo")?.objects()?.firstOrNull()
        val latitude = geo?.get("latitude").number()
        val longitude = geo?.get("longitude").number()
        val validGeo = latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0

        val categories =
            buildList {
                CATEGORY_BY_TYPE[eventType]?.let(::add)
                if (online) add("online")
            }
        val url = node["url"].text()?.let { resolve(pageUrl, it) }
        val id = node["@id"].text()?.let { resolve(pageUrl, it) }
        val status = node["eventStatus"].text().orEmpty()

        return ParsedEvent(
            externalId = id ?: url ?: ParsedEvent.fallbackId(title, start.instant),
            title = title,
            startsAt = start.instant,
            // An all-day endDate names the last day, so the event runs until the end of it.
            endsAt =
                end?.let { if (it.allDay) it.instant + 24.hours else it.instant }?.takeIf { it >= start.instant }
                    ?: if (start.allDay) start.instant + 24.hours else null,
            allDay = start.allDay,
            timeZone = start.zone,
            description = node["description"].text()?.let { Jsoup.parse(it).text() }.clean(),
            venue = place?.get("name").text().clean(500) ?: if (online) "Online" else null,
            address = place?.get("address")?.let(::address).clean(500),
            latitude = if (validGeo) latitude else null,
            longitude = if (validGeo) longitude else null,
            categories = categories,
            url = url,
            organiser =
                node["organizer"]
                    .objects()
                    .firstOrNull()
                    ?.get("name")
                    .text()
                    .clean(200)
                    ?: node["organizer"].text().clean(200),
            cancelled = status.endsWith("EventCancelled"),
        )
    }

    private data class DateValue(
        val instant: Instant,
        val allDay: Boolean,
        val zone: String?,
    )

    /** schema.org dates are ISO 8601, in 3 forms: a date, a date-time with an offset, a date-time without 1. */
    private fun parseDate(raw: String): DateValue? {
        val text = raw.trim()
        return try {
            when {
                text.length == 10 -> {
                    DateValue(
                        LocalDate
                            .parse(text)
                            .atStartOfDay(defaultZone)
                            .toInstant()
                            .toKotlin(),
                        true,
                        defaultZone.id,
                    )
                }

                text.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text.substringAfter('T')) -> {
                    DateValue(OffsetDateTime.parse(normaliseOffset(text)).toInstant().toKotlin(), false, null)
                }

                else -> {
                    DateValue(
                        LocalDateTime
                            .parse(text)
                            .atZone(defaultZone)
                            .toInstant()
                            .toKotlin(),
                        false,
                        defaultZone.id,
                    )
                }
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Turns +0700 into +07:00, which java.time needs. */
    private fun normaliseOffset(text: String): String = text.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")

    private fun address(element: JsonElement): String? =
        when (element) {
            is JsonPrimitive -> {
                element.contentOrNull
            }

            is JsonObject -> {
                listOf("streetAddress", "addressLocality", "addressRegion", "postalCode")
                    .mapNotNull { element[it].text() }
                    .plus(
                        listOfNotNull(
                            element["addressCountry"].let {
                                it.text() ?: it
                                    .objects()
                                    .firstOrNull()
                                    ?.get("name")
                                    .text()
                            },
                        ),
                    ).filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifEmpty { null }
            }

            is JsonArray -> {
                element.firstOrNull()?.let(::address)
            }
        }

    private fun resolve(
        base: String,
        value: String,
    ): String? =
        runCatching { URI(base).resolve(value.trim()).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    companion object {
        /** schema.org Event and its subtypes. Types whose name ends in "Event" count too. */
        val EVENT_TYPES =
            setOf(
                "Event",
                "BusinessEvent",
                "ChildrensEvent",
                "ComedyEvent",
                "CourseInstance",
                "DanceEvent",
                "DeliveryEvent",
                "EducationEvent",
                "EventSeries",
                "ExhibitionEvent",
                "Festival",
                "FoodEvent",
                "Hackathon",
                "LiteraryEvent",
                "MusicEvent",
                "PublicationEvent",
                "SaleEvent",
                "ScreeningEvent",
                "SocialEvent",
                "SportsEvent",
                "TheaterEvent",
                "VisualArtsEvent",
            )

        val CATEGORY_BY_TYPE =
            mapOf(
                "BusinessEvent" to "business",
                "ChildrensEvent" to "family",
                "ComedyEvent" to "comedy",
                "DanceEvent" to "dance",
                "EducationEvent" to "learning",
                "ExhibitionEvent" to "exhibition",
                "Festival" to "festival",
                "FoodEvent" to "food",
                "Hackathon" to "tech",
                "LiteraryEvent" to "books",
                "MusicEvent" to "music",
                "SaleEvent" to "market",
                "ScreeningEvent" to "film",
                "SocialEvent" to "social",
                "SportsEvent" to "sport",
                "TheaterEvent" to "theatre",
                "VisualArtsEvent" to "arts",
            )
    }
}

private fun JsonElement?.text(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> firstOrNull().text()
        else -> null
    }

private fun JsonElement?.strings(): List<String> =
    when (this) {
        is JsonPrimitive -> listOfNotNull(contentOrNull)
        is JsonArray -> flatMap { it.strings() }
        else -> emptyList()
    }

private fun JsonElement?.objects(): List<JsonObject> =
    when (this) {
        is JsonObject -> listOf(this)
        is JsonArray -> filterIsInstance<JsonObject>()
        else -> emptyList()
    }

private fun JsonElement?.number(): Double? =
    when (this) {
        is JsonPrimitive -> doubleOrNull ?: contentOrNull?.trim()?.toDoubleOrNull()
        else -> null
    }
