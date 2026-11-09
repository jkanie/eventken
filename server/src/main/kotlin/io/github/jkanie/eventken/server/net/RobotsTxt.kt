package io.github.jkanie.eventken.server.net

/**
 * A robots.txt file, read as RFC 9309 describes. The group for the most specific
 * user-agent wins (here "eventken", else "*"). Inside it, the longest matching
 * rule decides, and Allow wins a tie. Paths may use * and $ wildcards.
 */
class RobotsTxt private constructor(
    private val rules: List<Rule>,
) {
    private data class Rule(
        val allow: Boolean,
        val pattern: String,
    ) {
        val regex: Regex =
            Regex(
                buildString {
                    append('^')
                    pattern.forEachIndexed { index, c ->
                        when {
                            c == '*' -> append(".*")
                            c == '$' && index == pattern.lastIndex -> append('$')
                            else -> append(Regex.escape(c.toString()))
                        }
                    }
                },
            )
    }

    /** [path] is the URL path plus query, such as /events?page=2. */
    fun allows(path: String): Boolean {
        if (path == "/robots.txt") return true
        val best =
            rules
                .filter { it.regex.containsMatchIn(path) }
                .maxWithOrNull(compareBy<Rule> { it.pattern.length }.thenBy { it.allow })
        return best?.allow ?: true
    }

    companion object {
        /** Used when robots.txt does not exist (4xx): everything may be fetched. */
        val ALLOW_ALL = RobotsTxt(emptyList())

        /** Used when robots.txt cannot be read (5xx, network error): nothing may be fetched. */
        val DISALLOW_ALL = RobotsTxt(listOf(Rule(allow = false, pattern = "/")))

        /** RFC 9309 asks crawlers to read at least 500 KiB, and lets them ignore the rest. */
        const val MAX_BYTES = 500 * 1024

        fun parse(
            text: String,
            productToken: String,
        ): RobotsTxt {
            data class Group(
                val agents: MutableList<String> = mutableListOf(),
                val rules: MutableList<Rule> = mutableListOf(),
            )

            val groups = mutableListOf<Group>()
            var current: Group? = null
            var lastWasAgent = false
            text.take(MAX_BYTES).lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "user-agent" -> {
                        // Consecutive user-agent lines share 1 group.
                        val group = current?.takeIf { lastWasAgent } ?: Group().also(groups::add)
                        group.agents += value.lowercase()
                        current = group
                        lastWasAgent = true
                    }

                    "allow", "disallow" -> {
                        lastWasAgent = false
                        // An empty Disallow means "nothing is disallowed", so it adds no rule.
                        if (value.isNotEmpty()) current?.rules?.add(Rule(allow = key == "allow", pattern = value))
                    }

                    else -> {
                        lastWasAgent = false
                    }
                }
            }
            val token = productToken.lowercase()
            val matching = groups.filter { group -> group.agents.any { it == token } }
            val chosen = matching.ifEmpty { groups.filter { "*" in it.agents } }
            return RobotsTxt(chosen.flatMap { it.rules })
        }
    }
}
