package com.github.aratnitsyn.tictactoe.auth.schedule

import com.github.aratnitsyn.tictactoe.auth.account.LogoutOperation
import com.github.aratnitsyn.tictactoe.auth.account.LogoutService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class AuthMaintenance(
    private val source: DataSource,
    private val logout: LogoutService,
) {
    @Scheduled(fixedDelayString = "\${auth.cleanup-interval-ms:1000}")
    fun cleanup() {
        source.connection.use { connection ->
            com.github.aratnitsyn.tictactoe.auth.account.eraseExpired(
                connection,
                com.github.aratnitsyn.tictactoe.auth.account
                    .databaseTime(connection),
            )
        }
    }

    @Scheduled(fixedDelayString = "\${auth.logout-retry-ms:1000}")
    fun reconcile() {
        logout.pending().forEach { retry(it) }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve CLOSING and retry; do not log HTTP request/credentials.
    private fun retry(operation: LogoutOperation) {
        try {
            logout.reconcile(operation)
        } catch (_: Exception) {
            org.slf4j.LoggerFactory
                .getLogger(
                    AuthMaintenance::class.java,
                ).warn("Logout coordination unavailable; operation remains pending")
        }
    }
}
