package com.github.aratnitsyn.tictactoe.user.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.aratnitsyn.tictactoe.user.persistence.Inbox
import com.github.aratnitsyn.tictactoe.user.persistence.InboxDelivery
import com.github.aratnitsyn.tictactoe.user.persistence.registerInboxConsumer
import com.github.aratnitsyn.tictactoe.user.profile.AccountRegisteredHandler
import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

@Configuration
@EnableScheduling
class ProfileRuntimeConfiguration {
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
