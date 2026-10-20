package com.github.aratnitsyn.tictactoe.lobby.security
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.lobby.util.ResponseUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import java.security.MessageDigest

@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class LobbyAuthorization(
    @param:Value("\${lobby.service-token}") private val secret: String,
    private val mapper: ObjectMapper,
) : WebFilter {
    init {
        require(secret.length >= MIN_SERVICE_TOKEN_LENGTH)
    }

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): reactor.core.publisher.Mono<Void> {
        exchange.response.headers.cacheControl = "no-store"
        val health =
            exchange.request.path.value() == "/actuator/prometheus" ||
                exchange.request.path.value() == "/actuator/health" ||
                exchange.request.path
                    .value()
                    .startsWith("/actuator/health/")
        val supplied = exchange.request.headers.getFirst("X-Service-Token") ?: ""
        return if (health || MessageDigest.isEqual(secret.toByteArray(), supplied.toByteArray())) {
            chain.filter(exchange)
        } else {
            ResponseUtil.error(
                exchange,
                mapper,
                org.springframework.http.HttpStatus.UNAUTHORIZED
                    .value(),
                "UNAUTHENTICATED",
            )
        }
    }
}

private const val MIN_SERVICE_TOKEN_LENGTH = 32
