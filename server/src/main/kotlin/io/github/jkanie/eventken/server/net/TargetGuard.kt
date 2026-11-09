package io.github.jkanie.eventken.server.net

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class TargetRefusedException(
    message: String,
) : UnknownHostException(message)

/**
 * Keeps feed fetches away from internal networks (server-side request forgery).
 * An admin could add, or a feed could redirect to, an address such as
 * 169.254.169.254 (cloud metadata) or 127.0.0.1:5432. The guard checks the URL
 * when an admin saves it, then again on every connection, after DNS and after
 * every redirect, because a name can resolve to a new address at any time.
 */
class TargetGuard(
    private val allowPrivateTargets: Boolean,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** Returns why an address is refused, or null. Tests swap it to tell 2 loopback addresses apart. */
    private val addressPolicy: (InetAddress) -> String? = ::blockedReason,
) {
    /** Checks the shape of a URL and every address its host resolves to. Returns the parsed URL. */
    fun checkUrl(url: String): HttpUrl {
        val parsed = url.trim().toHttpUrlOrNull() ?: throw TargetRefusedException("Only http and https URLs can be feeds.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw TargetRefusedException("Feed URLs must not carry a user name or password.")
        }
        resolve(parsed.host)
        return parsed
    }

    /** Resolves [host] and refuses it when any address is in a blocked range. */
    fun resolve(host: String): List<InetAddress> {
        val addresses =
            try {
                resolver(host)
            } catch (e: UnknownHostException) {
                throw TargetRefusedException("Host $host does not resolve.")
            }
        if (addresses.isEmpty()) throw TargetRefusedException("Host $host does not resolve.")
        if (!allowPrivateTargets) {
            addresses.forEach { address ->
                addressPolicy(address)?.let { reason ->
                    throw TargetRefusedException(
                        "Address ${address.hostAddress} of $host is $reason. Set ALLOW_PRIVATE_TARGETS to allow it.",
                    )
                }
            }
        }
        return addresses
    }

    /**
     * For OkHttp: the client connects only to addresses this guard returned, so
     * there is no gap between the check and the connection for DNS to change.
     */
    val dns: Dns = Dns { hostname -> resolve(hostname) }

    companion object {
        private data class Range(
            val network: ByteArray,
            val prefix: Int,
            val reason: String,
        )

        private fun range(
            cidr: String,
            reason: String,
        ): Range {
            val (address, prefix) = cidr.split('/')
            return Range(InetAddress.getByName(address).address, prefix.toInt(), reason)
        }

        // IANA special-purpose registries for IPv4 (RFC 6890) and IPv6, plus multicast.
        private val V4 =
            listOf(
                range("0.0.0.0/8", "a reserved address"),
                range("10.0.0.0/8", "a private address"),
                range("100.64.0.0/10", "a shared (carrier-grade NAT) address"),
                range("127.0.0.0/8", "a loopback address"),
                range("169.254.0.0/16", "a link-local address"),
                range("172.16.0.0/12", "a private address"),
                range("192.0.0.0/24", "a reserved address"),
                range("192.0.2.0/24", "a documentation address"),
                range("192.88.99.0/24", "a reserved address"),
                range("192.168.0.0/16", "a private address"),
                range("198.18.0.0/15", "a benchmarking address"),
                range("198.51.100.0/24", "a documentation address"),
                range("203.0.113.0/24", "a documentation address"),
                range("224.0.0.0/4", "a multicast address"),
                range("240.0.0.0/4", "a reserved address"),
            )

        private val V6 =
            listOf(
                range("::/128", "the unspecified address"),
                range("::1/128", "a loopback address"),
                range("100::/64", "a discard-only address"),
                range("2001::/23", "a reserved address"),
                range("2001:db8::/32", "a documentation address"),
                range("fc00::/7", "a private (unique local) address"),
                range("fe80::/10", "a link-local address"),
                range("fec0::/10", "a site-local address"),
                range("ff00::/8", "a multicast address"),
            )

        /** Null when the address is public, else a short reason to show the admin. */
        fun blockedReason(address: InetAddress): String? {
            val bytes = address.address
            if (address is Inet6Address) {
                embeddedIpv4(bytes)?.let { v4 -> return blockedReason(v4) }
                return V6.firstOrNull { matches(bytes, it) }?.reason
            }
            if (address is Inet4Address) return V4.firstOrNull { matches(bytes, it) }?.reason
            return "an unknown kind of address"
        }

        /**
         * IPv6 forms that carry an IPv4 address inside: mapped (::ffff:a.b.c.d),
         * compatible (::a.b.c.d), NAT64 (64:ff9b::/96), 6to4 (2002::/16). The inner
         * address decides, so ::ffff:127.0.0.1 counts as loopback.
         */
        private fun embeddedIpv4(bytes: ByteArray): InetAddress? {
            fun zero(
                from: Int,
                to: Int,
            ) = (from until to).all { bytes[it] == 0.toByte() }
            val tail = bytes.copyOfRange(12, 16)
            return when {
                zero(0, 10) && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte() -> InetAddress.getByAddress(tail)

                zero(0, 12) && !zero(12, 16) && !(zero(12, 15) && bytes[15] == 1.toByte()) -> InetAddress.getByAddress(tail)

                bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() && bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() &&
                    zero(4, 12) -> InetAddress.getByAddress(tail)

                bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte() -> InetAddress.getByAddress(bytes.copyOfRange(2, 6))

                else -> null
            }
        }

        private fun matches(
            bytes: ByteArray,
            range: Range,
        ): Boolean {
            if (bytes.size != range.network.size) return false
            var bits = range.prefix
            var i = 0
            while (bits > 0) {
                val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
                if ((bytes[i].toInt() and mask) != (range.network[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }
    }
}

/** For error messages and logs: the URL without its query string, which may hold a key. */
fun HttpUrl.redacted(): String =
    newBuilder()
        .query(null)
        .fragment(null)
        .build()
        .toString()
