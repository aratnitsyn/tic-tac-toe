package com.github.aratnitsyn.tictactoe.lobby.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PullSubscribeOptions
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
class InboxTest {
    private val source =
        PGSimpleDataSource().apply {
            setURL(System.getenv("PG_JDBC_URL"))
            user = System.getenv("PGUSER")
            password = System.getenv("PGPASSWORD")
        }
    private val inbox = Inbox(source)

    @Test
    fun `committed effect is not repeated after lost acknowledgement`() {
        val eventId = UUID.randomUUID()
        val consumer = "test_$eventId"
        assertTrue(inbox.process(consumer, eventId) { })
        assertFalse(inbox.process(consumer, eventId) { error("Must not repeat committed effect") })
    }

    @Test
    fun `failed effect rolls back inbox marker`() {
        val eventId = UUID.randomUUID()
        val consumer = "test_$eventId"
        assertThrows(IllegalStateException::class.java) {
            inbox.process(consumer, eventId) { error("Injected before commit") }
        }
        assertTrue(inbox.process(consumer, eventId) { })
    }

    @Test
    fun `failed message retries then is parked before termination`() {
        assumeTrue(System.getenv("NATS_PASSWORD") != null)
        Broker().use { broker ->
            val delivery =
                InboxDelivery(
                    inbox,
                    broker.connection.jetStream(),
                    broker.consumer,
                    { _, _ -> error("Injected handler failure") },
                    listOf(Duration.ZERO),
                    2,
                )
            broker.publish(UUID.randomUUID())
            assertEquals(DeliveryResult.RETRY, delivery.handle(broker.fetch()))
            assertEquals(DeliveryResult.PARKED, delivery.handle(broker.fetch()))
            val parked = broker.connection.jetStreamManagement().getLastMessage("PARKING", "parking.${broker.consumer}")
            assertEquals(broker.consumer, ObjectMapper().readTree(parked.data)["consumer"].asText())
        }
    }

    @Test
    fun `parking failure leaves original message available`() {
        assumeTrue(System.getenv("NATS_PASSWORD") != null)
        Broker().use { broker ->
            val failed =
                java.lang.reflect.Proxy.newProxyInstance(
                    io.nats.client.JetStream::class.java.classLoader,
                    arrayOf(io.nats.client.JetStream::class.java),
                ) { _, _, _ -> throw java.io.IOException("Injected parking outage") } as io.nats.client.JetStream
            val delivery =
                InboxDelivery(
                    inbox,
                    failed,
                    broker.consumer,
                    { _, _ -> error("Injected handler failure") },
                    listOf(Duration.ZERO),
                    1,
                )
            broker.publish(UUID.randomUUID())
            assertThrows(java.io.IOException::class.java) { delivery.handle(broker.fetch()) }
            val retry =
                InboxDelivery(
                    inbox,
                    broker.connection.jetStream(),
                    broker.consumer,
                    { _, _ -> error("Injected handler failure") },
                    listOf(Duration.ZERO),
                    1,
                )
            assertEquals(DeliveryResult.PARKED, retry.handle(broker.fetch()))
        }
    }

    private class Broker : AutoCloseable {
        val consumer = "inbox_${UUID.randomUUID().toString().replace("-", "") }"
        private val stream = consumer.uppercase()
        private val subject = "it.$consumer.event"
        val connection =
            Nats.connect(
                Options
                    .Builder()
                    .server(System.getenv("NATS_URL"))
                    .userInfo(System.getenv("NATS_USER"), System.getenv("NATS_PASSWORD"))
                    .build(),
            )
        private val management = connection.jetStreamManagement()
        private val subscription: io.nats.client.JetStreamSubscription

        init {
            management.addStream(
                StreamConfiguration
                    .builder()
                    .name(stream)
                    .subjects(subject)
                    .replicas(1)
                    .maxMessages(1000)
                    .maxAge(Duration.ofHours(1))
                    .build(),
            )
            registerInboxConsumer(management, stream, consumer, subject)
            subscription = connection.jetStream().subscribe(subject, PullSubscribeOptions.bind(stream, consumer))
        }

        fun publish(eventId: UUID) {
            connection.jetStream().publish(subject, "{\"eventId\":\"$eventId\"}".toByteArray(Charsets.UTF_8))
        }

        fun fetch(): io.nats.client.Message = subscription.fetch(1, Duration.ofSeconds(5)).single()

        override fun close() {
            try {
                subscription.unsubscribe()
                management.deleteStream(stream)
            } finally {
                connection.close()
            }
        }
    }
}
