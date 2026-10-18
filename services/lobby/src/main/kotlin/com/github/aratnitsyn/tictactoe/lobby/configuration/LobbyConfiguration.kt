package com.github.aratnitsyn.tictactoe.lobby.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.lobby.room.CodeRateLimit
import com.github.aratnitsyn.tictactoe.lobby.room.GameClient
import com.github.aratnitsyn.tictactoe.lobby.room.HttpGameClient
import com.github.aratnitsyn.tictactoe.lobby.room.InvitationCode
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyDatabase
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyService
import com.github.aratnitsyn.tictactoe.lobby.room.RoomStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.WebClient
import javax.sql.DataSource

@Configuration
@EnableScheduling
class LobbyConfiguration {
    @Bean fun database(
        source: DataSource,
        mapper: ObjectMapper,
    ) = LobbyDatabase(source, mapper)

    @Bean fun store(db: LobbyDatabase) = RoomStore(db, InvitationCode()::next)

    @Bean fun service(
        db: LobbyDatabase,
        store: RoomStore,
        game: GameClient,
    ) = LobbyService(db, store, game)

    @Bean fun rate(
        db: LobbyDatabase,
        @Value("\${lobby.code-user-limit}") user: Int,
        @Value("\${lobby.code-ip-limit}") ip: Int,
    ) = CodeRateLimit(db, user, ip)

    @Bean fun game(
        @Value("\${lobby.game-url}") url: String,
        @Value("\${lobby.game-service-token}") token: String,
    ): GameClient =
        HttpGameClient(
            WebClient
                .builder()
                .baseUrl(url)
                .defaultHeader("X-Service-Token", token)
                .build(),
        )
}
