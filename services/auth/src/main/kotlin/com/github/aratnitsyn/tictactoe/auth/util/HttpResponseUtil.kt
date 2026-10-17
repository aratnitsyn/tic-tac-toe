package com.github.aratnitsyn.tictactoe.auth.util

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

object HttpResponseUtil {
    fun errorResponse(
        exchange: ServerWebExchange,
        mapper: ObjectMapper,
        status: HttpStatus,
        code: String,
    ): Mono<Void> {
        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body =
            mapper
                .createObjectNode()
                .put("code", code)
                .put("message", code)
                .put("serverTime", Instant.now().toString())
                .put("correlationId", UUID.randomUUID().toString())
        val bytes = mapper.writeValueAsBytes(body)
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }
}
