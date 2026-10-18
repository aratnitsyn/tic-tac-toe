package com.github.aratnitsyn.tictactoe.apigateway

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {
    @LocalServerPort
    private var port: Int = 0
    private val client get() = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$port").build()

    @Test
    fun `guests cannot read session and native bearer is introspected`() {
        client
            .get()
            .uri("/api/v1/auth/session")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/api/v1/auth/session")
            .header("Authorization", "Bearer valid")
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/api/v1/auth/session")
            .header("Authorization", "Bearer expired")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `ambiguous and malformed bearer credentials are rejected`() {
        client
            .get()
            .uri("/api/v1/auth/session")
            .header("Authorization", "Bearer valid")
            .cookie("SESSION", "valid")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/api/v1/auth/session")
            .header("Authorization", "Bearer ")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `web login requires allowed Origin and CSRF and preserves secure cookie`() {
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("Origin", ORIGIN)
            .bodyValue(mapOf("clientType" to "WEB"))
            .exchange()
            .expectStatus()
            .isForbidden
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("Origin", "https://attacker.invalid")
            .header("X-Client-Type", "NATIVE")
            .bodyValue(mapOf("clientType" to "NATIVE"))
            .exchange()
            .expectStatus()
            .isForbidden
        val csrf =
            client
                .get()
                .uri("/api/v1/auth/csrf")
                .header("Origin", ORIGIN)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .returnResult()
        val token = mapper.readTree(csrf.responseBody)["data"]["token"].asText()
        val cookie = requireNotNull(csrf.responseCookies.getFirst("__Host-CSRF"))
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("Origin", ORIGIN)
            .header("X-CSRF-TOKEN", token)
            .cookie(cookie.name, cookie.value)
            .bodyValue(mapOf("clientType" to "WEB"))
            .exchange()
            .expectStatus()
            .isOk
            .expectCookie()
            .httpOnly("SESSION", true)
            .expectCookie()
            .secure("SESSION", true)
    }

    @Test
    fun `native prelogin is allowed without browser cookies and spoofed service headers are overwritten`() {
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("X-Client-Type", "NATIVE")
            .header("X-Service-Token", "attacker")
            .header("X-User-Id", "spoofed")
            .header("X-Session-Id", "spoofed")
            .header("X-Client-IP", "spoofed")
            .bodyValue(mapOf("clientType" to "NATIVE"))
            .exchange()
            .expectStatus()
            .isOk
        assertEquals(SERVICE_TOKEN, seenServiceToken.get())
        assertEquals(null, seenUser.get())
        assertEquals(null, seenSession.get())
        assertNotEquals("spoofed", seenClientIp.get())
        assertEquals("127.0.0.1", seenClientIp.get())
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("X-Client-Type", "NATIVE")
            .cookie("SESSION", "valid")
            .bodyValue(mapOf("clientType" to "NATIVE"))
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `existing session login conflict passes through gateway`() {
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("X-Client-Type", "NATIVE")
            .bodyValue(mapOf("active" to true))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("SESSION_ALREADY_ACTIVE")
    }

    @Test
    fun `participation forwards only introspected identity and game credential`() {
        client
            .get()
            .uri("/api/v1/participation")
            .header("Authorization", "Bearer valid")
            .header("X-User-Id", "spoofed")
            .header("X-Session-Id", "spoofed")
            .header("X-Service-Token", "spoofed")
            .exchange()
            .expectStatus()
            .isOk
        assertEquals(USER_ID, seenUser.get())
        assertEquals(SESSION_ID, seenSession.get())
        assertEquals(GAME_TOKEN, seenServiceToken.get())
    }

    @Test
    fun `lobby forwards authenticated identity credential and peer IP without client spoofing`() {
        client
            .post()
            .uri("/api/v1/lobbies/join")
            .header("Authorization", "Bearer valid")
            .header("X-Client-Type", "NATIVE")
            .header("X-User-Id", "spoofed")
            .header("X-Session-Id", "spoofed")
            .header("X-Service-Token", "spoofed")
            .header("X-Client-IP", "spoofed")
            .bodyValue(mapOf("expectedVersion" to 0, "code" to "AAAAAAAAAA"))
            .exchange()
            .expectStatus()
            .isOk
        assertEquals(USER_ID, seenUser.get())
        assertEquals(SESSION_ID, seenSession.get())
        assertEquals(LOBBY_TOKEN, seenServiceToken.get())
        assertEquals("127.0.0.1", seenClientIp.get())
        client
            .get()
            .uri("/internal/v1/operations/" + UUID.randomUUID())
            .header("Authorization", "Bearer valid")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    companion object {
        private const val ORIGIN = "https://game.example.com"
        private val USER_ID = UUID.randomUUID().toString()
        private val SESSION_ID = UUID.randomUUID().toString()
        private val GAME_TOKEN = UUID.randomUUID().toString()
        private val LOBBY_TOKEN = UUID.randomUUID().toString()
        private val SERVICE_TOKEN = UUID.randomUUID().toString()
        private val mapper = jacksonObjectMapper()
        private val seenServiceToken = AtomicReference<String>()
        private val seenUser = AtomicReference<String>()
        private val seenSession = AtomicReference<String>()
        private val seenClientIp = AtomicReference<String>()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val body =
                        mapper.readTree(
                            exchange.requestBody
                                .readAllBytes()
                                .toString(Charsets.UTF_8)
                                .ifBlank { "{}" },
                        )
                    seenServiceToken.set(exchange.requestHeaders.getFirst("X-Service-Token"))
                    seenUser.set(exchange.requestHeaders.getFirst("X-User-Id"))
                    seenSession.set(exchange.requestHeaders.getFirst("X-Session-Id"))
                    seenClientIp.set(exchange.requestHeaders.getFirst("X-Client-IP"))
                    var status = 200
                    val response =
                        when {
                            exchange.requestURI.path.endsWith("introspect") -> {
                                if (body.path("token").asText() == "valid") {
                                    mapper.writeValueAsString(
                                        mapOf(
                                            "active" to true,
                                            "userId" to USER_ID,
                                            "sessionId" to SESSION_ID,
                                        ),
                                    )
                                } else {
                                    """{"active":false}"""
                                }
                            }
                            body.path("active").asBoolean() -> {
                                status = 409
                                """{"code":"SESSION_ALREADY_ACTIVE"}"""
                            }
                            else -> {
                                exchange.responseHeaders.add(
                                    "Set-Cookie",
                                    "SESSION=valid; Path=/api/v1; Secure; HttpOnly; SameSite=Strict",
                                )
                                "{}"
                            }
                        }
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, response.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(response.toByteArray()) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("gateway.auth-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("gateway.auth-service-token") { SERVICE_TOKEN }
            registry.add("gateway.web-origin") { ORIGIN }
            registry.add("gateway.game-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("gateway.game-service-token") { GAME_TOKEN }
            registry.add("gateway.lobby-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("gateway.lobby-service-token") { LOBBY_TOKEN }
        }

        @JvmStatic
        @AfterAll
        fun stopServer() = server.stop(0)
    }
}
