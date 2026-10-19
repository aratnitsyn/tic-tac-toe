package com.github.aratnitsyn.tictactoe.lobby.configuration

import com.github.aratnitsyn.tictactoe.lobby.persistence.Inbox
import com.github.aratnitsyn.tictactoe.lobby.persistence.InboxDelivery
import com.github.aratnitsyn.tictactoe.lobby.persistence.registerInboxConsumer
import com.github.aratnitsyn.tictactoe.lobby.room.GameClient
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyDatabase
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyLogoutHandler
import com.github.aratnitsyn.tictactoe.lobby.room.RoomStore
import com.github.aratnitsyn.tictactoe.lobby.schedule.LobbyLogoutConsumer
import io.nats.client.Connection
import io.nats.client.PullSubscribeOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class LobbyLogoutConfiguration {
    @Bean
    fun logoutConsumer(
        source: DataSource,
        connection: Connection,
        db: LobbyDatabase,
        store: RoomStore,
        game: GameClient,
    ): LobbyLogoutConsumer {
        val durable = "lobby-menu-v1"
        registerInboxConsumer(connection.jetStreamManagement(), "DOMAIN_EVENTS", durable, "events.ParticipationChanged")
        val subscription =
            connection.jetStream().subscribe(
                "events.ParticipationChanged",
                PullSubscribeOptions.bind("DOMAIN_EVENTS", durable),
            )
        val delivery =
            InboxDelivery(
                Inbox(source),
                connection.jetStream(),
                durable,
                LobbyLogoutHandler(db, store, game)::apply,
            )
        return LobbyLogoutConsumer(subscription, delivery)
    }
}
