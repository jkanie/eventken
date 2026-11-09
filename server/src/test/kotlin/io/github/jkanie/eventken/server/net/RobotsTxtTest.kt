package io.github.jkanie.eventken.server.net

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotsTxtTest {
    private val text =
        """
        # Sample robots.txt
        User-agent: *
        Disallow: /private/
        Disallow: /*.pdf$

        User-agent: OtherBot
        User-agent: eventken
        Disallow: /calendar/
        Allow: /calendar/public.ics
        Disallow: /search?

        User-agent: GreedyBot
        Disallow: /
        """.trimIndent()

    private val robots = RobotsTxt.parse(text, "eventken")

    @Test
    fun theNamedGroupReplacesTheStarGroup() {
        assertTrue(robots.allows("/private/members.html"), "the * group does not apply when a group names eventken")
        assertFalse(robots.allows("/calendar/members.ics"))
    }

    @Test
    fun theLongestRuleWins() {
        assertTrue(robots.allows("/calendar/public.ics"))
    }

    @Test
    fun queryStringsCount() {
        assertTrue(robots.allows("/search"))
        assertFalse(robots.allows("/search?q=jazz"))
    }

    @Test
    fun matchingIsCaseInsensitiveForTheAgentOnly() {
        val upper = RobotsTxt.parse("User-agent: EVENTKEN\nDisallow: /Feeds/", "eventken")
        assertFalse(upper.allows("/Feeds/a.ics"))
        assertTrue(upper.allows("/feeds/a.ics"), "paths are case-sensitive")
    }

    @Test
    fun starGroupWildcardsAndEndAnchors() {
        val star = RobotsTxt.parse(text, "someotherbot")
        assertFalse(star.allows("/private/members.html"))
        assertFalse(star.allows("/files/plan.pdf"))
        assertTrue(star.allows("/files/plan.pdf.html"))
    }

    @Test
    fun allowTheTieAndEmptyDisallow() {
        assertTrue(RobotsTxt.parse("User-agent: *\nDisallow: /a\nAllow: /a", "eventken").allows("/a"))
        assertTrue(RobotsTxt.parse("User-agent: *\nDisallow:", "eventken").allows("/anything"))
    }

    @Test
    fun robotsTxtItselfIsAlwaysAllowed() {
        assertTrue(RobotsTxt.DISALLOW_ALL.allows("/robots.txt"))
        assertFalse(RobotsTxt.DISALLOW_ALL.allows("/feed.ics"))
        assertTrue(RobotsTxt.ALLOW_ALL.allows("/feed.ics"))
    }
}
