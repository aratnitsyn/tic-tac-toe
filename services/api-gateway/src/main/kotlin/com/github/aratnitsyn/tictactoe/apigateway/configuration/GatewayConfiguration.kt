package com.github.aratnitsyn.tictactoe.apigateway.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.github.aratnitsyn.tictactoe.apigateway.security.GatewayErrors
import com.github.aratnitsyn.tictactoe.apigateway.util.nativeRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException
import org.springframework.security.oauth2.server.resource.introspection.ReactiveOpaqueTokenIntrospector
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

@Configuration
class GatewayConfiguration(
    @param:Value("\${gateway.auth-url}") private val authUrl: String,
    @param:Value("\${gateway.auth-service-token}") private val serviceToken: String,
) {
    @Bean
    fun introspector(): ReactiveOpaqueTokenIntrospector {
        val client =
            WebClient
                .builder()
                .baseUrl(authUrl)
                .defaultHeader("X-Service-Token", serviceToken)
                .build()
        return ReactiveOpaqueTokenIntrospector { token ->
            client
                .post()
                .uri("/internal/v1/sessions/introspect")
                .bodyValue(mapOf("token" to token))
                .retrieve()
                .bodyToMono(JsonNode::class.java)
                .timeout(Duration.ofSeconds(INTROSPECTION_TIMEOUT_SECONDS))
                .map { body ->
                    if (!body.path("active").asBoolean()) throw BadOpaqueTokenException("UNAUTHENTICATED")
                    val userId = body.path("userId").asText()
                    DefaultOAuth2AuthenticatedPrincipal(
                        userId,
                        mapOf("sub" to userId, "sessionId" to body.path("sessionId").asText()),
                        emptyList(),
                    )
                }
        }
    }

    @Bean
    fun security(
        http: ServerHttpSecurity,
        introspector: ReactiveOpaqueTokenIntrospector,
        errors: GatewayErrors,
    ): SecurityWebFilterChain =
        http
            .csrf {
                it
                    .csrfTokenRepository(csrfRepository())
                    .requireCsrfProtectionMatcher(csrfMatcher())
                    .accessDeniedHandler {
                        exchange,
                        _,
                        ->
                        errors.write(exchange, org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN")
                    }
            }.exceptionHandling {
                it
                    .authenticationEntryPoint {
                        exchange,
                        _,
                        ->
                        errors.write(exchange, org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
                    }.accessDeniedHandler {
                        exchange,
                        _,
                        ->
                        errors.write(exchange, org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN")
                    }
            }.httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeExchange {
                it
                    .pathMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                    ).permitAll()
                it
                    .pathMatchers("/api/v1/**")
                    .authenticated()
                    .anyExchange()
                    .denyAll()
            }.oauth2ResourceServer { resource ->
                resource.authenticationEntryPoint { exchange, _ ->
                    errors.write(exchange, org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
                }
                resource.bearerTokenConverter(credentials()).opaqueToken { it.introspector(introspector) }
            }.build()

    private fun csrfRepository() =
        CookieServerCsrfTokenRepository().apply {
            setCookieName("__Host-CSRF")
            setHeaderName("X-CSRF-TOKEN")
            setCookieCustomizer {
                it
                    .secure(true)
                    .httpOnly(true)
                    .sameSite("Strict")
                    .path("/")
            }
        }

    private fun csrfMatcher() =
        ServerWebExchangeMatcher { exchange ->
            val safe = exchange.request.method in listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)
            val native = nativeRequest(exchange)
            if (safe || native) {
                ServerWebExchangeMatcher.MatchResult.notMatch()
            } else {
                ServerWebExchangeMatcher.MatchResult.match()
            }
        }

    private fun credentials(): ServerAuthenticationConverter {
        val bearer =
            org.springframework.security.oauth2.server.resource.web.server.authentication
                .ServerBearerTokenAuthenticationConverter()
        return ServerAuthenticationConverter { exchange ->
            val cookie =
                exchange.request.cookies
                    .getFirst("SESSION")
                    ?.value
            bearer
                .convert(exchange)
                .flatMap { authentication ->
                    if (cookie != null) {
                        Mono.error(
                            org.springframework.security.authentication
                                .BadCredentialsException("Ambiguous credentials"),
                        )
                    } else {
                        Mono.just(authentication)
                    }
                }.switchIfEmpty(
                    Mono.defer {
                        cookie?.let { Mono.just(BearerTokenAuthenticationToken(it)) } ?: Mono.empty()
                    },
                )
        }
    }

    @Bean
    fun routes(builder: RouteLocatorBuilder): RouteLocator =
        builder
            .routes()
            .route("auth") {
                it
                    .path("/api/v1/auth/**")
                    .filters { filters ->
                        filters
                            .removeRequestHeader("X-User-Id")
                            .removeRequestHeader("X-Session-Id")
                            .setRequestHeader("X-Service-Token", serviceToken)
                            .filter { exchange, chain ->
                                val request =
                                    exchange.request
                                        .mutate()
                                        .headers { headers ->
                                            headers.set(
                                                "X-Client-IP",
                                                exchange.request.remoteAddress
                                                    ?.address
                                                    ?.hostAddress ?: "unknown",
                                            )
                                        }.build()
                                chain.filter(exchange.mutate().request(request).build())
                            }
                    }.uri(authUrl)
            }.build()
}

private const val INTROSPECTION_TIMEOUT_SECONDS = 3L
