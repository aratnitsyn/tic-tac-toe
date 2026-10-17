package com.github.aratnitsyn.tictactoe.auth.http

import com.github.aratnitsyn.tictactoe.auth.persistence.JetStreamPublisher
import com.github.aratnitsyn.tictactoe.auth.persistence.OutboxWorker
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
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

@Component
class AuthOutbox(
    private val worker: OutboxWorker,
) {
    @Scheduled(fixedDelayString = "\${events.poll-ms:100}")
    fun publish() {
        repeat(OUTBOX_BATCH_SIZE) { if (!worker.publishOne()) return }
    }
}

private const val OUTBOX_BATCH_SIZE = 100
