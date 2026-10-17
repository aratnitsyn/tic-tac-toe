package com.github.aratnitsyn.tictactoe.auth.account.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.AuthFailure
import com.github.aratnitsyn.tictactoe.auth.account.ClientType
import com.github.aratnitsyn.tictactoe.auth.account.Credential
import com.github.aratnitsyn.tictactoe.auth.account.RegistrationService
import com.github.aratnitsyn.tictactoe.auth.account.SessionIdentity
import com.github.aratnitsyn.tictactoe.auth.account.SessionService
import com.github.aratnitsyn.tictactoe.auth.util.WebFluxUtil.blocking
import com.github.aratnitsyn.tictactoe.auth.util.WebFluxUtil.token
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

@Suppress("MagicNumber") // HTTP status codes and initial protocol version.
@RestController
class AuthController(
    private val registration: RegistrationService,
    private val sessions: SessionService,
    private val mapper: ObjectMapper,
) {
    @PostMapping("/api/v1/auth/register")
    fun register(
        @RequestHeader("Idempotency-Key") commandId: UUID,
        @RequestBody request: Credential,
    ): Mono<ResponseEntity<JsonNode>> =
        blocking {
            ResponseEntity.status(201).body(registration.register(commandId, request))
        }

    @PostMapping("/api/v1/auth/login")
    fun login(
        @RequestHeader("Idempotency-Key") commandId: UUID,
        @RequestBody request: Credential,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<JsonNode>> =
        blocking {
            val result = sessions.login(commandId, request)
            if (request.clientType == ClientType.WEB) {
                exchange.response.addCookie(
                    ResponseCookie
                        .from("SESSION", result.token)
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("Strict")
                        .path("/api/v1")
                        .build(),
                )
            }
            val body = sessionBody(result.identity)
            if (request.clientType ==
                ClientType.NATIVE
            ) {
                body["data"].let { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("token", result.token) }
            }
            ResponseEntity.ok(body)
        }

    @GetMapping("/api/v1/auth/session")
    fun session(exchange: ServerWebExchange): Mono<JsonNode> =
        blocking {
            sessionBody(sessions.introspect(token(exchange)) ?: throw AuthFailure("UNAUTHENTICATED", 401))
        }

    @PostMapping("/internal/v1/sessions/introspect")
    fun introspect(
        @RequestBody request: JsonNode,
    ): Mono<JsonNode> =
        blocking {
            val identity = sessions.introspect(request.path("token").asText())
            val result = mapper.createObjectNode().put("active", identity != null)
            if (identity !=
                null
            ) {
                result
                    .put(
                        "userId",
                        identity.userId.toString(),
                    ).put("sessionId", identity.sessionId.toString())
                    .put("expiresAt", identity.expiresAt.toString())
            }
            result
        }

    private fun sessionBody(identity: SessionIdentity): JsonNode {
        val data =
            mapper
                .createObjectNode()
                .put("userId", identity.userId.toString())
                .put("sessionId", identity.sessionId.toString())
                .put("expiresAt", identity.expiresAt.toString())
                .put("state", "ACTIVE")
        return mapper
            .createObjectNode()
            .put("serverTime", Instant.now().toString())
            .put("version", 1)
            .set("data", data)
    }
}
