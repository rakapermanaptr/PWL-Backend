package id.primawash.api

import id.primawash.api.common.AppConfig
import id.primawash.api.common.BuildInfo
import id.primawash.api.db.DatabaseFactory
import id.primawash.api.plugins.configureMonitoring
import id.primawash.api.plugins.configureRouting
import id.primawash.api.plugins.configureSerialization
import id.primawash.api.plugins.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.log
import javax.sql.DataSource

/**
 * Entry point referenced by `application.conf`; started through `io.ktor.server.netty.EngineMain`
 * (`./gradlew run`). Migrations run at boot so a fresh staging container is usable immediately.
 */
fun Application.module() {
    val config = AppConfig.fromEnvironment()
    val dataSource = DatabaseFactory.dataSource(config.database)
    val applied = DatabaseFactory.migrate(dataSource)
    DatabaseFactory.connect(dataSource)
    log.info("Terhubung ke database ({} migrasi diterapkan saat boot)", applied)

    apiModule(dataSource, BuildInfo.VERSION)
}

/**
 * Plugin and route wiring, without any I/O of its own — tests install this against their own
 * DataSource. Order matters: monitoring first so every later failure carries a `requestId`, then
 * serialization, then the central error envelope, then the routes.
 */
fun Application.apiModule(
    dataSource: DataSource,
    appVersion: String,
) {
    configureMonitoring()
    configureSerialization()
    configureStatusPages()
    configureRouting(dataSource, appVersion)
}
