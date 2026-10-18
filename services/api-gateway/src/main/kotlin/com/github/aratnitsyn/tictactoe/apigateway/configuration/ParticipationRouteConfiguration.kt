package com.github.aratnitsyn.tictactoe.apigateway.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal

@Configuration
class ParticipationRouteConfiguration(
    @param:Value("\${gateway.game-url}") private val gameUrl: String,
    @param:Value("\${gateway.game-service-token}") private val gameToken: String,
) {
    @Bean
    fun participationRoute(builder: RouteLocatorBuilder): RouteLocator =
        builder
            .routes()
            .route("participation") {
                it
                    .path("/api/v1/participation", "/api/v1/matches/*", "/api/v1/matches/*/ready")
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
                                                headers.set("X-Service-Token", gameToken)
                                                headers.set("X-User-Id", principal.getAttribute<String>("sub"))
                                                headers.set("X-Session-Id", principal.getAttribute<String>("sessionId"))
                                            }.build()
                                    chain.filter(exchange.mutate().request(request).build())
                                }
                            }
                    }.uri(gameUrl)
            }.build()
}
