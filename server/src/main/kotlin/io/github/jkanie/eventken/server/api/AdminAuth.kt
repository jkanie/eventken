package io.github.jkanie.eventken.server.api

import io.github.jkanie.eventken.shared.model.ApiError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest

class AdminAuthConfig {
    /** Null means the admin endpoints are off. */
    var token: String? = null
}

/**
 * Bearer-token check for the admin routes. The comparison takes the same time
 * wherever the tokens differ, so response times do not leak the token byte by byte.
 */
val AdminAuth =
    createRouteScopedPlugin("AdminAuth", ::AdminAuthConfig) {
        val expected = pluginConfig.token?.toByteArray()
        onCall { call ->
            if (expected == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("admin_disabled", "The admin endpoints are off. Set EVENTKEN_ADMIN_TOKEN to turn them on."),
                )
                return@onCall
            }
            val given =
                call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(7)
                    ?.trim()
            if (given == null || !MessageDigest.isEqual(given.toByteArray(), expected)) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"eventken admin\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError("unauthorised", "Send a valid admin token as Authorization: Bearer <token>."),
                )
            }
        }
    }
