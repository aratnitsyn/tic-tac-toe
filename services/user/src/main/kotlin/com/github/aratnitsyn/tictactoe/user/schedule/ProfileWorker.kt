package com.github.aratnitsyn.tictactoe.user.schedule

import com.github.aratnitsyn.tictactoe.user.persistence.InboxDelivery
import io.nats.client.JetStreamSubscription
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

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
