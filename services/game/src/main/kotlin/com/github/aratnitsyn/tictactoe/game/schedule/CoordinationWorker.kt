package com.github.aratnitsyn.tictactoe.game.schedule
import com.github.aratnitsyn.tictactoe.game.participation.OperationStore
import com.github.aratnitsyn.tictactoe.game.participation.ParticipationService
import com.github.aratnitsyn.tictactoe.game.persistence.OutboxWorker
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CoordinationWorker(
    private val operations: OperationStore,
    private val service: ParticipationService,
    private val outbox: OutboxWorker,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${coordination.retry-delay}")
    @Suppress("TooGenericExceptionCaught") // One unavailable operation must not prevent retries of other operations.
    fun reconcile() {
        operations.pending().forEach { id ->
            try {
                service.apply(id)
            } catch (failure: Exception) {
                logger.warn("Coordination retry operationId={} failure={}", id, failure.javaClass.simpleName)
            }
        }
    }

    @Scheduled(fixedDelayString = "\${coordination.retry-delay}")
    fun publish() {
        outbox.publishOne()
    }
}
