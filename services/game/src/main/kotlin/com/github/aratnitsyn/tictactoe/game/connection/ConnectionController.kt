package com.github.aratnitsyn.tictactoe.game.connection

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.game.util.ResponseUtil.blocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ConnectionController(
    private val lease: ConnectionLease,
) {
    @PostMapping("/internal/v1/connections/renew")
    fun renew(
        @RequestBody payload: JsonNode,
    ) = blocking { lease.renew(payload) }
}
