package com.github.aratnitsyn.tictactoe.game.security
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.game.participation.CoordinationFailure
import com.github.aratnitsyn.tictactoe.game.util.ResponseUtil
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebExceptionHandler
import reactor.core.publisher.Mono

@Component
@Order(ERROR_HANDLER_ORDER)
class GameErrorHandler(
    private val mapper: ObjectMapper,
) : WebExceptionHandler {
    override fun handle(
        exchange: ServerWebExchange,
        error: Throwable,
    ): Mono<Void> {
        if (exchange.response.isCommitted) return Mono.error(error)
        return when (error) {
            is CoordinationFailure -> ResponseUtil.error(exchange, mapper, error.status, error.code)
            is org.springframework.web.server.ServerWebInputException, is IllegalArgumentException,
            is com.fasterxml.jackson.core.JsonProcessingException,
            ->
                ResponseUtil.error(
                    exchange,
                    mapper,
                    org.springframework.http.HttpStatus.BAD_REQUEST
                        .value(),
                    "VALIDATION_ERROR",
                )
            is java.sql.SQLException ->
                ResponseUtil.error(
                    exchange,
                    mapper,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                        .value(),
                    "SERVICE_UNAVAILABLE",
                )
            else -> Mono.error(error)
        }
    }
}

private const val ERROR_HANDLER_ORDER = -2
