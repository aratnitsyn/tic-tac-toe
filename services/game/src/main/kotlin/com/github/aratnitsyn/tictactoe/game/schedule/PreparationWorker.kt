package com.github.aratnitsyn.tictactoe.game.schedule

import com.github.aratnitsyn.tictactoe.game.preparation.PreparationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class PreparationWorker(
    private val service: PreparationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${coordination.retry-delay}")
    @Suppress("TooGenericExceptionCaught") // A failed aggregate is retried from its persisted deadline.
    fun tick() {
        service.due().forEach { id ->
            try {
                service.tick(id)
            } catch (failure: Exception) {
                logger.warn("Preparation retry matchId={} failure={}", id, failure.javaClass.simpleName)
            }
        }
    }
}
