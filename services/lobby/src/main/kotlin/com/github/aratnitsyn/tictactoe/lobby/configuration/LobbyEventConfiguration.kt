package com.github.aratnitsyn.tictactoe.lobby.configuration
import com.github.aratnitsyn.tictactoe.lobby.persistence.Inbox
import com.github.aratnitsyn.tictactoe.lobby.persistence.InboxDelivery
import com.github.aratnitsyn.tictactoe.lobby.persistence.JetStreamPublisher
import com.github.aratnitsyn.tictactoe.lobby.persistence.OutboxWorker
import com.github.aratnitsyn.tictactoe.lobby.persistence.registerInboxConsumer
import com.github.aratnitsyn.tictactoe.lobby.room.GameClient
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyDatabase
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyReturnHandler
import com.github.aratnitsyn.tictactoe.lobby.room.RoomStore
import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.sql.DataSource

@Configuration
class LobbyEventConfiguration {
    @Bean(destroyMethod = "close")
    fun nats(
        @Value("\${events.url}") url: String,
        @Value("\${events.user}") user: String,
        @Value("\${events.password}") password: String,
    ): Connection =
        Nats.connect(
            Options
                .Builder()
                .server(url)
                .userInfo(user, password)
                .build(),
        )

    @Bean
    fun subscription(connection: Connection): JetStreamSubscription {
        registerInboxConsumer(
            connection.jetStreamManagement(),
            "DOMAIN_EVENTS",
            "lobby-return-v1",
            "events.MatchCancelled",
        )
        return connection.jetStream().subscribe(
            "events.MatchCancelled",
            PullSubscribeOptions.bind("DOMAIN_EVENTS", "lobby-return-v1"),
        )
    }

    @Bean
    fun delivery(
        source: DataSource,
        connection: Connection,
        db: LobbyDatabase,
        store: RoomStore,
        game: GameClient,
    ) = InboxDelivery(
        Inbox(source),
        connection.jetStream(),
        "lobby-return-v1",
        LobbyReturnHandler(db, store, game)::apply,
    )

    @Bean fun outbox(
        source: DataSource,
        connection: Connection,
    ) = OutboxWorker(source, JetStreamPublisher(connection.jetStream()), UUID.randomUUID().toString())
}
