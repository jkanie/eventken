package io.github.jkanie.eventken.shared.search

import kotlin.time.Instant

enum class SortOrder(
    val wire: String,
) {
    /** Best text match first. Needs a text query. */
    RELEVANCE("relevance"),

    /** Soonest start first. */
    START("start"),

    /** Nearest first. Needs a location. */
    DISTANCE("distance"),
    ;

    companion object {
        fun fromWire(value: String): SortOrder? = entries.firstOrNull { it.wire == value }
    }
}

class InvalidSearchQuery(
    val problems: List<String>,
) : IllegalArgumentException(problems.joinToString(" "))

/**
 * 1 search, as the API takes it. The server and both apps use this class, so the
 * rules below hold in 1 place: the apps catch a bad query before they send it, and
 * the server refuses the same query with the same message.
 */
data class SearchQuery(
    val text: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusKm: Double? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val categories: List<String> = emptyList(),
    val sort: SortOrder? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
    val includeCancelled: Boolean = false,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** The sort the server applies when the query names none. */
    val effectiveSort: SortOrder
        get() =
            sort ?: when {
                !text.isNullOrBlank() -> SortOrder.RELEVANCE
                else -> SortOrder.START
            }

    fun problems(): List<String> {
        val problems = mutableListOf<String>()
        if (text != null && text.length > MAX_TEXT) problems += "q must be at most $MAX_TEXT characters."
        if ((latitude == null) != (longitude == null)) problems += "lat and lon go together: give both or neither."
        if (latitude != null && latitude !in -90.0..90.0) problems += "lat must be between -90 and 90."
        if (longitude != null && longitude !in -180.0..180.0) problems += "lon must be between -180 and 180."
        if (radiusKm != null) {
            if (!hasLocation) problems += "radiusKm needs lat and lon."
            if (radiusKm <= 0.0 || radiusKm > MAX_RADIUS_KM) problems += "radiusKm must be above 0 and at most $MAX_RADIUS_KM."
        }
        if (from != null && to != null && from > to) problems += "from must not be after to."
        if (categories.size > MAX_CATEGORIES) problems += "At most $MAX_CATEGORIES categories."
        if (limit !in 1..MAX_LIMIT) problems += "limit must be between 1 and $MAX_LIMIT."
        if (offset !in 0..MAX_OFFSET) problems += "offset must be between 0 and $MAX_OFFSET."
        if (sort == SortOrder.RELEVANCE && text.isNullOrBlank()) problems += "sort=relevance needs q."
        if (sort == SortOrder.DISTANCE && !hasLocation) problems += "sort=distance needs lat and lon."
        return problems
    }

    fun validated(): SearchQuery {
        val problems = problems()
        if (problems.isNotEmpty()) throw InvalidSearchQuery(problems)
        return this
    }

    /** The query string parameters for GET /api/events, in a stable order. */
    fun toParameters(): List<Pair<String, String>> =
        buildList {
            text?.takeIf { it.isNotBlank() }?.let { add("q" to it) }
            latitude?.let { add("lat" to it.toString()) }
            longitude?.let { add("lon" to it.toString()) }
            radiusKm?.let { add("radiusKm" to it.toString()) }
            from?.let { add("from" to it.toString()) }
            to?.let { add("to" to it.toString()) }
            categories.forEach { add("category" to it) }
            sort?.let { add("sort" to it.wire) }
            if (limit != DEFAULT_LIMIT) add("limit" to limit.toString())
            if (offset != 0) add("offset" to offset.toString())
            if (includeCancelled) add("includeCancelled" to "true")
        }

    fun nextPage(): SearchQuery = copy(offset = offset + limit)

    class Builder {
        private var query = SearchQuery()

        fun text(value: String?) = apply { query = query.copy(text = value?.trim()?.ifEmpty { null }) }

        fun near(
            latitude: Double,
            longitude: Double,
            radiusKm: Double? = null,
        ) = apply { query = query.copy(latitude = latitude, longitude = longitude, radiusKm = radiusKm) }

        fun anywhere() = apply { query = query.copy(latitude = null, longitude = null, radiusKm = null) }

        fun between(
            from: Instant?,
            to: Instant?,
        ) = apply { query = query.copy(from = from, to = to) }

        fun category(name: String) =
            apply {
                val clean = normaliseCategory(name)
                if (clean.isNotEmpty() && clean !in query.categories) query = query.copy(categories = query.categories + clean)
            }

        fun sort(order: SortOrder?) = apply { query = query.copy(sort = order) }

        fun page(
            limit: Int,
            offset: Int = 0,
        ) = apply { query = query.copy(limit = limit, offset = offset) }

        fun includeCancelled(value: Boolean) = apply { query = query.copy(includeCancelled = value) }

        /** Builds the query, or throws [InvalidSearchQuery] that lists every problem. */
        fun build(): SearchQuery = query.validated()
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_OFFSET = 10_000
        const val MAX_TEXT = 200
        const val MAX_CATEGORIES = 10
        const val MAX_RADIUS_KM = 500.0

        fun builder() = Builder()

        fun normaliseCategory(name: String): String = name.trim().lowercase()

        /**
         * Reads a query from request parameters, as the server receives them. Every
         * parameter that does not parse is reported, together with the rule problems.
         */
        fun fromParameters(values: (String) -> List<String>): SearchQuery {
            val problems = mutableListOf<String>()

            fun single(name: String): String? = values(name).firstOrNull()?.trim()?.ifEmpty { null }

            fun double(name: String): Double? =
                single(name)?.let { raw ->
                    raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: null.also { problems += "$name must be a number." }
                }

            fun int(name: String): Int? =
                single(name)?.let { raw -> raw.toIntOrNull() ?: null.also { problems += "$name must be a whole number." } }

            fun instant(name: String): Instant? =
                single(name)?.let { raw ->
                    runCatching { Instant.parse(raw) }.getOrNull()
                        ?: null.also { problems += "$name must be an ISO 8601 instant such as 2026-07-01T18:00:00Z." }
                }

            val sortValue = single("sort")
            val sort =
                sortValue?.let { raw ->
                    SortOrder.fromWire(raw) ?: null.also { problems += "sort must be 1 of: relevance, start, distance." }
                }
            val includeCancelled =
                when (val raw = single("includeCancelled")) {
                    null, "false" -> false
                    "true" -> true
                    else -> false.also { problems += "includeCancelled must be true or false, not $raw." }
                }
            val query =
                SearchQuery(
                    text = single("q"),
                    latitude = double("lat"),
                    longitude = double("lon"),
                    radiusKm = double("radiusKm"),
                    from = instant("from"),
                    to = instant("to"),
                    categories =
                        values("category")
                            .flatMap { it.split(',') }
                            .map(::normaliseCategory)
                            .filter { it.isNotEmpty() }
                            .distinct(),
                    sort = sort,
                    limit = int("limit") ?: DEFAULT_LIMIT,
                    offset = int("offset") ?: 0,
                    includeCancelled = includeCancelled,
                )
            val all = problems + query.problems()
            if (all.isNotEmpty()) throw InvalidSearchQuery(all)
            return query
        }
    }
}
