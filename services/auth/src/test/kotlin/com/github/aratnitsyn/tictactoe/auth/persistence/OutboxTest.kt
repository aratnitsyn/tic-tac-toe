package com.github.aratnitsyn.tictactoe.auth.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.postgresql.ds.PGSimpleDataSource
import java.time.Duration
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "PG_JDBC_URL", matches = ".+")
class OutboxTest {
    private val mapper = ObjectMapper()
    private val source =
        PGSimpleDataSource().apply {
            setURL(System.getenv("PG_JDBC_URL"))
            user = System.getenv("PGUSER")
            password = System.getenv("PGPASSWORD")
        }

    @Test
    fun `crash after broker ack retries same event`() {
        assumeTrue(System.getenv("NATS_PASSWORD") != null)
        val eventId = UUID.randomUUID()
        val stream = "IT_${eventId.toString().replace("-", "") }"
        val prefix = "it.$eventId"
        val options =
            Options
                .Builder()
                .server(System.getenv("NATS_URL"))
                .userInfo(System.getenv("NATS_USER"), System.getenv("NATS_PASSWORD"))
                .build()
        Nats.connect(options).use { connection ->
            val management = connection.jetStreamManagement()
            management.addStream(
                StreamConfiguration
                    .builder()
                    .name(stream)
                    .subjects("$prefix.>")
                    .storageType(StorageType.File)
                    .discardPolicy(DiscardPolicy.New)
                    .replicas(1)
                    .maxMessages(1000)
                    .maxBytes(1048576)
                    .maxAge(Duration.ofHours(1))
                    .build(),
            )
            try {
                val event = OutboxEvent(eventId, eventId, 1, "AccountRegistered", mapper.readTree("{}"))
                source.connection.use { db ->
                    db.autoCommit = false
                    Outbox.enqueue(db, event)
                    db.commit()
                }
                val publisher = JetStreamPublisher(connection.jetStream(), prefix)
                val failed = OutboxWorker(source, publisher, "$eventId-first") { error("Injected after publish ack") }
                assertThrows(IllegalStateException::class.java) { failed.publishOne() }
                assertTrue(isPending(eventId))
                expireLease(eventId)
                val retry = OutboxWorker(source, publisher, "$eventId-restart")
                assertTrue(retry.publishOne())
                assertEquals(1L, management.getStreamInfo(stream).streamState.msgCount)
                assertFalse(retry.publishOne())
            } finally {
                deleteEvent(eventId)
                management.deleteStream(stream)
            }
        }
    }

    @Test
    fun `rolled back enqueue leaves no event`() {
        val eventId = UUID.randomUUID()
        source.connection.use { connection ->
            connection.autoCommit = false
            Outbox.enqueue(connection, OutboxEvent(eventId, eventId, 1, "AccountRegistered", mapper.readTree("{}")))
            connection.rollback()
        }
        source.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM outbox WHERE event_id=?").use {
                it.setObject(1, eventId)
                it.executeQuery().use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    private fun isPending(eventId: UUID): Boolean =
        source.connection.use { db ->
            db.prepareStatement("SELECT published_at IS NULL FROM outbox WHERE event_id=?").use {
                it.setObject(1, eventId)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getBoolean(1)
                }
            }
        }

    private fun expireLease(eventId: UUID) {
        source.connection.use { db ->
            db
                .prepareStatement(
                    "UPDATE outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?",
                ).use {
                    it.setObject(1, eventId)
                    it.executeUpdate()
                }
        }
    }

    private fun deleteEvent(eventId: UUID) {
        source.connection.use { db ->
            db.prepareStatement("DELETE FROM outbox WHERE event_id=?").use {
                it.setObject(1, eventId)
                it.executeUpdate()
            }
        }
    }
}
