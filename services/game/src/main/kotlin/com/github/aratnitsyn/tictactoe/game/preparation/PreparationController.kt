package com.github.aratnitsyn.tictactoe.game.preparation

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.util.ResponseUtil.blocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class PreparationController(
    private val service: PreparationService,
) {
    @GetMapping("/api/v1/matches/{matchId}")
    fun snapshot(
        @RequestHeader("X-User-Id") user: UUID,
        @PathVariable matchId: UUID,
    ) = blocking { service.snapshot(user, matchId) }

    @PostMapping("/api/v1/matches/{matchId}/ready")
    fun ready(
        @RequestHeader("X-User-Id") user: UUID,
        @PathVariable matchId: UUID,
        @RequestHeader("Idempotency-Key") command: UUID,
        @RequestBody payload: JsonNode,
    ) = blocking {
        service.ready(user, matchId, command, payload).let { ResponseEntity.status(it.status).body(it.body) }
    }

    @PostMapping("/internal/v1/connections/observe")
    fun observe(
        @RequestHeader("Idempotency-Key") command: UUID,
        @RequestBody payload: JsonNode,
    ) = blocking {
        val user = UUID.fromString(payload.path("userId").asText())
        val match = UUID.fromString(payload.path("matchId").asText())
        service.observe(user, match, command, payload).let { ResponseEntity.status(it.status).body(it.body) }
    }
}
