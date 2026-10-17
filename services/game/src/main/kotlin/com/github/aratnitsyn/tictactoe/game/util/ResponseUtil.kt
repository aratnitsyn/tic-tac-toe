package com.github.aratnitsyn.tictactoe.game.util
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Instant
import java.util.UUID

object ResponseUtil {
    fun <T : Any> blocking(effect: () -> T): Mono<T> =
        Mono
            .fromCallable(effect)
            .subscribeOn(Schedulers.boundedElastic())

    fun error(
        exchange: ServerWebExchange,
        mapper: ObjectMapper,
        status: Int,
        code: String,
    ): Mono<Void> {
        exchange.response.statusCode =
            org.springframework.http.HttpStatusCode
                .valueOf(status)
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val correlation =
            exchange.request.headers
                .getFirst("X-Correlation-Id")
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.randomUUID()
        val body =
            mapper
                .createObjectNode()
                .put("code", code)
                .put("message", code)
                .put("serverTime", Instant.now().toString())
                .put("correlationId", correlation.toString())
        val bytes = mapper.writeValueAsBytes(body)
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }
}
