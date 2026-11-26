package com.phonebridge.relay.db

import com.phonebridge.relay.RelayConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

class DatabaseFactory(private val dataSource: DataSource) {

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load()
            .migrate()
    }

    fun <T> inTransaction(block: (Connection) -> T): T {
        dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }

    fun healthCheck(): Boolean = runCatching {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1").use { ps ->
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }.getOrDefault(false)

    fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    companion object {
        fun createFromConfig(config: RelayConfig): DatabaseFactory {
            val hc = HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = 16
                minimumIdle = 2
                connectionTimeout = 10_000
                idleTimeout = 300_000
                isAutoCommit = true
            }
            return DatabaseFactory(HikariDataSource(hc))
        }
    }
}
