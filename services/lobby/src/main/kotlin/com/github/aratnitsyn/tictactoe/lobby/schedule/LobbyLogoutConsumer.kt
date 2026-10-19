package com.github.aratnitsyn.tictactoe.lobby.schedule

import com.github.aratnitsyn.tictactoe.lobby.persistence.InboxDelivery
import io.nats.client.JetStreamSubscription
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

class LobbyLogoutConsumer(
    private val subscription: JetStreamSubscription,
    private val delivery: InboxDelivery,
) {
    @Scheduled(fixedDelayString = "\${lobby.retry-ms}")
    fun poll() {
        subscription.fetch(LOGOUT_BATCH_SIZE, Duration.ofMillis(LOGOUT_POLL_MILLIS)).forEach(delivery::handle)
    }
}

private const val LOGOUT_BATCH_SIZE = 16
private const val LOGOUT_POLL_MILLIS = 250L
