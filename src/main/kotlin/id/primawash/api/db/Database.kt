package id.primawash.api.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.primawash.api.common.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * Hikari + Exposed + Flyway wiring. One pool per process; a transaction never opens a second
 * connection (CLAUDE.md Invariant 2).
 */
object DatabaseFactory {
    fun dataSource(config: DatabaseConfig): HikariDataSource {
        val hikari =
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                // Timestamps are stored and returned in UTC regardless of the host's timezone.
                connectionInitSql = "SET TIME ZONE 'UTC'"
                poolName = "pwl-cashier"
                validate()
            }
        return HikariDataSource(hikari)
    }

    /**
     * A failed statement is never retried by Exposed: a unique violation is a business answer
     * (PIN taken, phone already registered), and the Service decides what it means.
     */
    fun connect(dataSource: DataSource): Database =
        Database.connect(
            datasource = dataSource,
            databaseConfig =
                org.jetbrains.exposed.v1.core
                    .DatabaseConfig { defaultMaxAttempts = 1 },
        )

    /**
     * Applies the versioned migrations in `db/migration`. Runs at boot and from
     * `./gradlew flywayMigrate`; a merged migration is never edited, only followed by a new one.
     */
    fun migrate(dataSource: DataSource): Int =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
            .migrate()
            .migrationsExecuted
}
