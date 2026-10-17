package com.github.aratnitsyn.tictactoe.auth.account

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

class IssuedTicket(
    val ticket: String,
    val expiresAt: Instant,
)

class WsTickets(
    private val redis: StringRedisTemplate,
    private val sessions: SessionService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun issue(
        token: String,
        commandId: UUID = UUID.randomUUID(),
    ): IssuedTicket {
        val identity =
            sessions.introspect(token) ?: throw AuthFailure(
                "UNAUTHENTICATED",
                org.springframework.http.HttpStatus.UNAUTHORIZED
                    .value(),
            )
        val ticket = randomToken()
        val expiry = clock.instant().plusSeconds(TICKET_SECONDS)
        val value = "${identity.userId}/${identity.sessionId}/${expiry.toEpochMilli()}"
        val result =
            requireNotNull(
                redis.execute(
                    ISSUE,
                    listOf("ws-command:${identity.sessionId}/$commandId", "ws-ticket:${tokenHash(ticket)}"),
                    "$ticket|${expiry.toEpochMilli()}",
                    value,
                ),
            )
        val fields = result.split('|')
        val storedExpiry = Instant.ofEpochMilli(fields[1].toLong())
        if (!clock.instant().isBefore(storedExpiry)) throw AuthFailure("DEADLINE_EXCEEDED", HttpStatus.CONFLICT.value())
        return IssuedTicket(fields[0], storedExpiry)
    }

    fun consume(ticket: String): SessionIdentity? {
        // GETDEL is atomic across Auth replicas. A failed session check still burns the ticket.
        val value = redis.execute(CONSUME, listOf("ws-ticket:${tokenHash(ticket)}"))
        val fields = value?.split('/') ?: emptyList()
        if (fields.size != TICKET_FIELDS) return null
        val expiry = Instant.ofEpochMilli(fields[2].toLong())
        return if (clock.instant().isBefore(expiry)) {
            sessions.touchIdentity(UUID.fromString(fields[0]), UUID.fromString(fields[1]))
        } else {
            null
        }
    }

    companion object {
        private const val TICKET_SECONDS = 30L
        private const val TICKET_FIELDS = 3
        private val ISSUE =
            DefaultRedisScript(
                "local old=redis.call('GET',KEYS[1]); if old then return old end; " +
                    "redis.call('SET',KEYS[1],ARGV[1],'EX',86400); " +
                    "redis.call('SET',KEYS[2],ARGV[2],'EX',30); return ARGV[1]",
                String::class.java,
            )
        private val CONSUME = DefaultRedisScript("return redis.call('GETDEL', KEYS[1])", String::class.java)
    }
}
