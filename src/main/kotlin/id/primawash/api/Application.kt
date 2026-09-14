package id.primawash.api

import id.primawash.api.common.AppConfig
import id.primawash.api.common.BuildInfo
import id.primawash.api.db.DatabaseFactory
import id.primawash.api.plugins.ApiSettings
import id.primawash.api.plugins.configureDependencyInjection
import id.primawash.api.plugins.configureMonitoring
import id.primawash.api.plugins.configureRateLimit
import id.primawash.api.plugins.configureRouting
import id.primawash.api.plugins.configureSerialization
import id.primawash.api.plugins.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.log
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import javax.sql.DataSource

/**
 * Entry point referenced by `application.conf`; started through `io.ktor.server.netty.EngineMain`
 * (`./gradlew run`).
 *
 * In dev the process migrates while booting. In staging/production it does not: migrations are a
 * pre-deploy job (`bin/pwl-migrate`) run as the schema owner, so the API role never needs schema
 * rights and two rolling instances never race for the Flyway lock.
 */
fun Application.module() {
    val config = AppConfig.fromEnvironment()
    val dataSource = DatabaseFactory.dataSource(config.database)
    if (config.runMigrationsOnBoot) {
        val applied = DatabaseFactory.migrate(dataSource)
        log.info("Terhubung ke database ({} migrasi diterapkan saat boot)", applied)
    } else {
        log.info("Terhubung ke database (migrasi ditangani job pre-deploy, tidak dijalankan di sini)")
    }

    apiModule(
        dataSource = dataSource,
        database = DatabaseFactory.connect(dataSource),
        settings =
            ApiSettings(
                jwtSecret = config.jwtSecret,
                pinPepper = config.pinPepper,
                minAppVersion = config.minAppVersion,
                appVersion = BuildInfo.VERSION,
            ),
    )
}

/**
 * Plugin and route wiring, without any I/O of its own — tests install this against their own
 * database and clock. Order matters: monitoring first so every later failure carries a `requestId`,
 * then serialization, the central error envelope, dependency injection, rate limiting, and routes.
 */
fun Application.apiModule(
    dataSource: DataSource,
    database: Database,
    settings: ApiSettings,
    clock: Clock = Clock.systemUTC(),
) {
    configureMonitoring()
    configureSerialization()
    configureStatusPages()
    configureDependencyInjection(database, settings, clock)
    configureRateLimit()
    configureRouting(dataSource, settings)
}
