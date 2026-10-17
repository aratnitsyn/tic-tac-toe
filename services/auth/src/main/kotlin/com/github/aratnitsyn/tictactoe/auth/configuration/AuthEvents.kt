package com.github.aratnitsyn.tictactoe.auth.configuration

import com.github.aratnitsyn.tictactoe.auth.persistence.JetStreamPublisher
import com.github.aratnitsyn.tictactoe.auth.persistence.OutboxWorker
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.sql.DataSource

@Configuration
class AuthEvents {
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
    fun outbox(
        source: DataSource,
        connection: Connection,
    ) = OutboxWorker(source, JetStreamPublisher(connection.jetStream()), UUID.randomUUID().toString())
}
