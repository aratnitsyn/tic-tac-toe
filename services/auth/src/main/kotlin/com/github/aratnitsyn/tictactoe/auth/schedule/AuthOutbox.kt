package com.github.aratnitsyn.tictactoe.auth.schedule

import com.github.aratnitsyn.tictactoe.auth.persistence.OutboxWorker
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

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
