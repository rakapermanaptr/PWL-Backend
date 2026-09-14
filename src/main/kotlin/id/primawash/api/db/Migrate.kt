package id.primawash.api.db

import id.primawash.api.common.AppConfig
import org.slf4j.LoggerFactory

/** Entry point of `./gradlew flywayMigrate`. */
fun main() {
    val logger = LoggerFactory.getLogger("Migrate")
    val config = AppConfig.fromEnvironment()
    DatabaseFactory.dataSource(config.database).use { dataSource ->
        val applied = DatabaseFactory.migrate(dataSource)
        logger.info("Flyway selesai: {} migrasi diterapkan ({})", applied, config.database.url)
    }
}
