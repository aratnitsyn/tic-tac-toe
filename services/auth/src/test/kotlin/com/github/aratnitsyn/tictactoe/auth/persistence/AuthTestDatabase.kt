package com.github.aratnitsyn.tictactoe.auth.persistence

import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

// Each test gets an isolated schema; outbox workers must not consume another test's events.
class AuthTestDatabase : AutoCloseable {
    private val schema = "it_" + UUID.randomUUID().toString().replace("-", "")
    val source =
        PGSimpleDataSource().apply {
            setURL(System.getenv("PG_JDBC_URL"))
            user = System.getenv("PGUSER")
            password = System.getenv("PGPASSWORD")
            currentSchema = schema
        }

    init {
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                Files.list(Path.of("db/migration")).use { paths ->
                    paths.sorted().forEach { statement.execute(Files.readString(it)) }
                }
            }
        }
    }

    override fun close() {
        source.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }
}
