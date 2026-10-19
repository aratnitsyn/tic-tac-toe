package com.github.aratnitsyn.tictactoe.game.logout

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.util.ResponseUtil.blocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class LogoutController(
    private val service: LogoutService,
) {
    @PostMapping("/internal/v1/participation/logout")
    fun logout(
        @RequestHeader("Idempotency-Key") id: UUID,
        @RequestBody payload: JsonNode,
    ) = blocking { service.execute(id, payload) }
}
