package com.github.aratnitsyn.tictactoe.apigateway.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal

@Configuration
class MatchmakingRouteConfiguration(
    @param:Value("\${gateway.matchmaking-url}") private val matchmakingUrl: String,
    @param:Value("\${gateway.matchmaking-service-token}") private val matchmakingToken: String,
) {
    @Bean
    fun matchmakingRoute(builder: RouteLocatorBuilder): RouteLocator =
        builder
            .routes()
            .route("matchmaking") {
                it
                    .path("/api/v1/matchmaking/queue", "/api/v1/matchmaking/queue/cancel")
                    .filters { filters ->
                        filters
                            .removeRequestHeader("Authorization")
                            .removeRequestHeader("Cookie")
                            .filter { exchange, chain ->
                                exchange.getPrincipal<Authentication>().flatMap { authentication ->
                                    val principal = authentication.principal as OAuth2AuthenticatedPrincipal
                                    val request =
                                        exchange.request
                                            .mutate()
                                            .headers { headers ->
                                                headers.set("X-Service-Token", matchmakingToken)
                                                headers.set(
                                                    "X-Client-IP",
                                                    exchange.request.remoteAddress
                                                        ?.address
                                                        ?.hostAddress ?: "unknown",
                                                )
                                                headers.set("X-User-Id", principal.getAttribute<String>("sub"))
                                                headers.set("X-Session-Id", principal.getAttribute<String>("sessionId"))
                                            }.build()
                                    chain.filter(exchange.mutate().request(request).build())
                                }
                            }
                    }.uri(matchmakingUrl)
            }.build()
}
