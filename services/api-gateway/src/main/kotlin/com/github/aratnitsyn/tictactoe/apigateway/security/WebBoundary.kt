package com.github.aratnitsyn.tictactoe.apigateway.security

import com.github.aratnitsyn.tictactoe.apigateway.util.RequestUtil.nativeRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebBoundary(
    @Value("\${gateway.web-origin}") private val origin: String,
    private val errors: GatewayErrorHandler,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        exchange.response.headers.cacheControl = "no-store"
        val supplied = exchange.request.headers.origin
        val unsafe = exchange.request.method !in listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)
        val rejectOrigin = supplied != null && supplied != origin
        val missingOrigin = unsafe && !nativeRequest(exchange) && supplied == null
        if (rejectOrigin || missingOrigin) {
            return errors.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN")
        }
        return chain.filter(exchange)
    }
}

@RestController
class CsrfController {
    @GetMapping("/api/v1/auth/csrf")
    fun csrf(exchange: ServerWebExchange): Mono<Map<String, Any>> {
        val csrf: Mono<CsrfToken> = requireNotNull(exchange.getAttribute(CsrfToken::class.java.name))
        return csrf.map {
            mapOf(
                "serverTime" to Instant.now().toString(),
                "version" to 1,
                "data" to mapOf("token" to it.token, "headerName" to it.headerName),
            )
        }
    }
}
