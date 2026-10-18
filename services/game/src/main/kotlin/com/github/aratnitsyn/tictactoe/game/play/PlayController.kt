package com.github.aratnitsyn.tictactoe.game.play

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.util.ResponseUtil.blocking
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
class PlayController(
    private val service: PlayService,
) {
    @GetMapping("/api/v1/games/{matchId}")
    fun snapshot(
        @RequestHeader("X-User-Id") user: UUID,
        @PathVariable matchId: UUID,
    ) = blocking { service.snapshot(user, matchId) }

    @PostMapping("/api/v1/games/{matchId}/moves", "/api/v1/games/{matchId}/surrender")
    fun command(
        @RequestHeader("X-User-Id") user: UUID,
        @PathVariable matchId: UUID,
        @RequestHeader("Idempotency-Key") command: UUID,
        @RequestBody payload: JsonNode,
        exchange: ServerWebExchange,
    ) = blocking {
        val kind =
            if (exchange.request.path
                    .value()
                    .endsWith("/moves")
            ) {
                "MOVE"
            } else {
                "SURRENDER"
            }
        service.command(user, matchId, command, kind, payload).let { ResponseEntity.status(it.status).body(it.body) }
    }
}
