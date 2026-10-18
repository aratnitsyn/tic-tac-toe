package com.github.aratnitsyn.tictactoe.lobby.room.http

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.lobby.room.CodeRateLimit
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyCommand
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyService
import com.github.aratnitsyn.tictactoe.lobby.util.ResponseUtil.blocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

@RestController
class LobbyController(
    private val service: LobbyService,
    private val rate: CodeRateLimit,
) {
    @GetMapping("/api/v1/lobbies/{roomId}")
    fun snapshot(
        @RequestHeader("X-User-Id") user: UUID,
        @PathVariable roomId: UUID,
    ) = blocking { service.snapshot(user, roomId) }

    @PostMapping("/api/v1/lobbies", "/api/v1/lobbies/join", "/api/v1/lobbies/{roomId}/leave")
    fun command(
        @RequestHeader("X-User-Id") user: UUID,
        @RequestHeader("Idempotency-Key") id: UUID,
        @RequestBody payload: JsonNode,
        exchange: ServerWebExchange,
    ) = blocking {
        val path = exchange.request.path.value()
        val kind =
            when {
                path.endsWith("/join") -> "JOIN"
                path.endsWith("/leave") -> "LEAVE"
                else -> "CREATE"
            }
        if (kind == "JOIN") rate.check(user.toString(), exchange.request.headers.getFirst("X-Client-IP") ?: "unknown")
        val room = if (kind == "LEAVE") UUID.fromString(path.substringAfter("/lobbies/").substringBefore('/')) else null
        val correlation =
            exchange.request.headers
                .getFirst("X-Correlation-Id")
                ?.let(UUID::fromString) ?: id
        val response = service.execute(LobbyCommand(user, id, kind, payload, correlation, room))
        ResponseEntity.status(response.status).body(response.body)
    }
}
