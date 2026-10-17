package com.github.aratnitsyn.tictactoe.user.profile

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.user.persistence.Inbox
import com.github.aratnitsyn.tictactoe.user.persistence.InboxDelivery
import com.github.aratnitsyn.tictactoe.user.persistence.registerInboxConsumer
import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import javax.sql.DataSource

@Configuration
@EnableScheduling
class ProfileRuntime {
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
            "user-profiles-v1",
            "events.AccountRegistered",
        )
        return connection.jetStream().subscribe(
            "events.AccountRegistered",
            PullSubscribeOptions.bind("DOMAIN_EVENTS", "user-profiles-v1"),
        )
    }

    @Bean
    fun delivery(
        source: DataSource,
        connection: Connection,
        mapper: ObjectMapper,
    ): InboxDelivery =
        InboxDelivery(
            Inbox(source),
            connection.jetStream(),
            "user-profiles-v1",
            AccountRegisteredHandler(mapper)::apply,
        )
}

@Component
class ProfileWorker(
    private val subscription: JetStreamSubscription,
    private val delivery: InboxDelivery,
) {
    @Scheduled(fixedDelayString = "\${events.poll-ms:100}")
    fun poll() {
        subscription.fetch(INBOX_BATCH_SIZE, Duration.ofMillis(INBOX_POLL_MILLIS)).forEach(delivery::handle)
    }
}

private const val INBOX_BATCH_SIZE = 16
private const val INBOX_POLL_MILLIS = 250L
