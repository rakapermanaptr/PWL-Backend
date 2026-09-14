package id.primawash.api.plugins

import id.primawash.api.auth.AccessTokens
import id.primawash.api.auth.AuthRepository
import id.primawash.api.auth.AuthService
import id.primawash.api.auth.PinHasher
import id.primawash.api.auth.SessionService
import id.primawash.api.branch.BranchRepository
import id.primawash.api.branch.BranchService
import id.primawash.api.catalog.CatalogRepository
import id.primawash.api.catalog.CatalogService
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.SecureTokens
import id.primawash.api.customer.CustomerRepository
import id.primawash.api.customer.CustomerService
import id.primawash.api.db.ExposedTransactionRunner
import id.primawash.api.db.TransactionRunner
import id.primawash.api.device.DeviceRepository
import id.primawash.api.device.DeviceService
import id.primawash.api.staff.StaffRepository
import id.primawash.api.staff.StaffService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.KoinIsolated
import org.koin.logger.slf4jLogger
import java.time.Clock

/** Secrets and settings the API process needs; never includes `WA_*` (Meta is the final milestone). */
data class ApiSettings(
    val jwtSecret: String,
    val pinPepper: String,
    val minAppVersion: String,
    val appVersion: String,
)

/**
 * Koin wiring. Isolated per `Application` so test applications never share a container. Every
 * Service gets the same [TransactionRunner], so an action and the cross-feature work it delegates
 * run on one connection.
 */
fun Application.configureDependencyInjection(
    database: Database,
    settings: ApiSettings,
    clock: Clock,
) {
    install(KoinIsolated) {
        slf4jLogger()
        modules(coreModule(database, settings, clock), featureModule())
    }
}

private fun coreModule(
    database: Database,
    settings: ApiSettings,
    clock: Clock,
): Module =
    module {
        single { clock }
        single { settings }
        single<TransactionRunner> { ExposedTransactionRunner(database) }
        single { AuditWriter(get()) }
        single { PinHasher(settings.pinPepper) }
        single { SecureTokens() }
        single { AccessTokens(settings.jwtSecret, get()) }
    }

private fun featureModule(): Module =
    module {
        single { BranchRepository() }
        single { StaffRepository() }
        single { AuthRepository() }
        single { DeviceRepository() }
        single { CatalogRepository() }
        single { CustomerRepository() }

        single { BranchService(get(), get(), get()) }
        single { SessionService(get(), get()) }
        single { StaffService(get(), get(), get(), get(), get(), get(), get()) }
        single { DeviceService(get(), get(), get(), get(), get()) }
        single { AuthService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single { CatalogService(get(), get(), get(), get()) }
        single { CustomerService(get(), get(), get(), get()) }
    }
