package com.github.aratnitsyn.tictactoe.apigateway.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal

@Configuration
class LobbyRouteConfiguration(
    @param:Value("\${gateway.lobby-url}") private val lobbyUrl: String,
    @param:Value("\${gateway.lobby-service-token}") private val lobbyToken: String,
) {
    @Bean
    fun lobbyRoute(builder: RouteLocatorBuilder): RouteLocator =
        builder
            .routes()
            .route("lobby") {
                it
                    .path("/api/v1/lobbies", "/api/v1/lobbies/**")
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
                                                headers.set("X-Service-Token", lobbyToken)
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
                    }.uri(lobbyUrl)
            }.build()
}
