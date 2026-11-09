package io.github.jkanie.eventken.server.net

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetGuardTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "127.255.255.254", "169.254.169.254", "172.16.0.1",
            "172.31.255.255", "192.0.0.8", "192.0.2.10", "192.168.1.1", "198.18.0.1", "198.51.100.7", "203.0.113.9",
            "224.0.0.1", "240.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "fec0::1",
            "ff02::1", "2001:db8::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::127.0.0.1", "64:ff9b::a9fe:a9fe",
            "2002:7f00:0001::1", "2001:0:4136:e378::1",
        ],
    )
    fun refusesSpecialPurposeAddresses(address: String) {
        assertNotNull(TargetGuard.blockedReason(InetAddress.getByName(address)), address)
    }

    @ParameterizedTest
    @ValueSource(strings = ["93.184.215.14", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "64:ff9b::808:808"])
    fun allowsPublicAddresses(address: String) {
        assertNull(TargetGuard.blockedReason(InetAddress.getByName(address)), address)
    }

    private fun guard(
        vararg addresses: String,
        allowPrivate: Boolean = false,
    ) = TargetGuard(allowPrivate, resolver = { host ->
        addresses.map { InetAddress.getByAddress(host, InetAddress.getByName(it).address) }
    })

    @Test
    fun aNameIsRefusedWhenAnyOfItsAddressesIsPrivate() {
        val error =
            assertFailsWith<TargetRefusedException> { guard("93.184.215.14", "10.0.0.5").checkUrl("https://events.example.org/feed.ics") }
        assertEquals(
            "Address 10.0.0.5 of events.example.org is a private address. Set ALLOW_PRIVATE_TARGETS to allow it.",
            error.message,
        )
    }

    @Test
    fun publicNamesPass() {
        assertEquals("events.example.org", guard("93.184.215.14").checkUrl("https://events.example.org/feed.ics").host)
    }

    @Test
    fun allowPrivateTargetsTurnsTheCheckOff() {
        assertEquals("localhost", guard("127.0.0.1", allowPrivate = true).checkUrl("http://localhost:8089/a.ics").host)
    }

    @Test
    fun onlyHttpAndHttpsWithoutCredentials() {
        val g = guard("93.184.215.14")
        assertFailsWith<TargetRefusedException> { g.checkUrl("file:///etc/passwd") }
        assertFailsWith<TargetRefusedException> { g.checkUrl("ftp://example.org/a.ics") }
        assertFailsWith<TargetRefusedException> { g.checkUrl("https://user:secret@example.org/a.ics") }
    }

    @Test
    fun ipLiteralsInEveryFormAreChecked() {
        val real = TargetGuard(allowPrivateTargets = false)
        listOf("http://127.0.0.1/", "http://[::1]/", "http://2130706433/", "http://0x7f.0.0.1/", "http://169.254.169.254/latest/meta-data/")
            .forEach { url -> assertFailsWith<TargetRefusedException>(url) { real.checkUrl(url) } }
    }

    @Test
    fun unresolvableHosts() {
        val error = assertFailsWith<TargetRefusedException> { guard().checkUrl("https://nowhere.example/") }
        assertTrue(error.message!!.contains("does not resolve"))
    }
}
