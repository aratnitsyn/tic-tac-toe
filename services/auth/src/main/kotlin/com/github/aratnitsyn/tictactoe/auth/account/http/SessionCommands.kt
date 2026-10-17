package com.github.aratnitsyn.tictactoe.auth.account.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.auth.account.LogoutService
import com.github.aratnitsyn.tictactoe.auth.account.WsTickets
import com.github.aratnitsyn.tictactoe.auth.util.blocking
import com.github.aratnitsyn.tictactoe.auth.util.token
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

@RestController
class SessionCommands(
    private val tickets: WsTickets,
    private val logout: LogoutService,
    private val mapper: ObjectMapper,
) {
    @PostMapping("/api/v1/auth/ws-ticket")
    fun issue(
        @RequestHeader("Idempotency-Key") commandId: UUID,
        exchange: ServerWebExchange,
    ): Mono<JsonNode> =
        blocking {
            val issued = tickets.issue(token(exchange), commandId)
            val data =
                mapper
                    .createObjectNode()
                    .put("ticket", issued.ticket)
                    .put("expiresAt", issued.expiresAt.toString())
            mapper
                .createObjectNode()
                .put("serverTime", Instant.now().toString())
                .put("version", 1)
                .set("data", data)
        }

    @PostMapping("/internal/v1/ws-tickets/consume")
    fun consume(
        @RequestBody body: JsonNode,
    ): Mono<JsonNode> =
        blocking {
            val identity = tickets.consume(body.path("ticket").asText())
            val response = mapper.createObjectNode().put("active", identity != null)
            if (identity !=
                null
            ) {
                response
                    .put(
                        "userId",
                        identity.userId.toString(),
                    ).put("sessionId", identity.sessionId.toString())
                    .put("expiresAt", identity.expiresAt.toString())
            }
            response
        }

    @PostMapping("/api/v1/auth/logout")
    fun logout(
        @RequestHeader("Idempotency-Key") commandId: UUID,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<JsonNode>> =
        blocking {
            val operation = logout.begin(token(exchange), commandId)
            exchange.response.addCookie(
                ResponseCookie
                    .from("SESSION", "")
                    .secure(true)
                    .httpOnly(true)
                    .sameSite("Strict")
                    .path("/api/v1")
                    .maxAge(0)
                    .build(),
            )
            val data =
                mapper
                    .createObjectNode()
                    .put(
                        "operationId",
                        operation.operationId.toString(),
                    ).put("state", if (operation.completed) "CLOSED" else "CLOSING")
            val response =
                mapper
                    .createObjectNode()
                    .put("serverTime", Instant.now().toString())
                    .put("version", 1)
                    .set<JsonNode>("data", data)
            ResponseEntity.accepted().body(response)
        }
}
