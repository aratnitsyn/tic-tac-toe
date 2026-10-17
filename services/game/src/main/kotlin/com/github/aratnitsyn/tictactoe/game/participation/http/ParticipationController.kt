package com.github.aratnitsyn.tictactoe.game.participation.http
import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationService
import com.github.aratnitsyn.tictactoe.game.util.ResponseUtil.blocking
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

@RestController
class ParticipationController(
    private val service: ParticipationService,
) {
    @GetMapping("/api/v1/participation")
    fun snapshot(
        @RequestHeader("X-User-Id") userId: UUID,
    ) = blocking { service.snapshot(userId) }

    @GetMapping("/internal/v1/operations/{operationId}")
    fun operation(
        @PathVariable operationId: UUID,
    ) = blocking { service.result(operationId) }

    @PostMapping("/internal/v1/participation/claim", "/internal/v1/rooms/register", "/internal/v1/matches/assign-pair")
    fun command(
        @RequestHeader("Idempotency-Key") id: UUID,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ) = blocking {
        if (UUID.fromString(body.path("operationId").asText()) != id) throw CoordinationFailure("IDEMPOTENCY_CONFLICT")
        val correlation =
            exchange.request.headers
                .getFirst("X-Correlation-Id")
                ?.let(UUID::fromString) ?: id
        val kind =
            when (exchange.request.path.value()) {
                "/internal/v1/rooms/register" -> "REGISTER_ROOM"
                "/internal/v1/matches/assign-pair" -> "ASSIGN_PAIR"
                else -> "CLAIM"
            }
        service.execute(kind, body, correlation)
    }
}
