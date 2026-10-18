package com.github.aratnitsyn.tictactoe.lobby.schedule

import com.github.aratnitsyn.tictactoe.lobby.persistence.InboxDelivery
import com.github.aratnitsyn.tictactoe.lobby.persistence.OutboxWorker
import com.github.aratnitsyn.tictactoe.lobby.room.LobbyService
import io.nats.client.JetStreamSubscription
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class LobbyWorker(
    private val service: LobbyService,
    private val outbox: OutboxWorker,
    private val subscription: JetStreamSubscription,
    private val delivery: InboxDelivery,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // One unavailable operation must not stop recovery of the remaining operations.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedDelayString = "\${lobby.retry-ms}")
    fun reconcile() {
        service.pending().forEach { id ->
            try {
                service.resume(id)
            } catch (failure: Exception) {
                logger.warn("Lobby coordination pending operationId={} cause={}", id, failure.javaClass.simpleName)
            }
        }
    }

    @Scheduled(fixedDelayString = "\${lobby.retry-ms}")
    fun publish() {
        outbox.publishOne()
    }

    @Scheduled(fixedDelayString = "\${lobby.retry-ms}")
    fun poll() {
        subscription.fetch(BATCH_SIZE, Duration.ofMillis(POLL_MILLIS)).forEach(delivery::handle)
    }
}

private const val BATCH_SIZE = 16
private const val POLL_MILLIS = 250L
