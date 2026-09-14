package id.primawash.api.db

import id.primawash.api.common.DatabaseConfig
import org.slf4j.LoggerFactory

/**
 * Entry point of `./gradlew flywayMigrate` and of `bin/pwl-migrate`, the pre-deploy job in
 * staging/production. Reads only the `DATABASE_*` variables: the job runs as the schema owner
 * (`pwl_migrator`) and has no business holding the JWT secret or the PIN pepper.
 */
fun main() {
    val logger = LoggerFactory.getLogger("Migrate")
    val database = DatabaseConfig.fromEnvironment()
    DatabaseFactory.dataSource(database).use { dataSource ->
        val applied = DatabaseFactory.migrate(dataSource)
        logger.info("Flyway selesai: {} migrasi diterapkan ({})", applied, database.url)
    }
}
