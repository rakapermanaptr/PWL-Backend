package id.primawash.api.tools

import id.primawash.api.auth.AuthRepository
import id.primawash.api.auth.PinHasher
import id.primawash.api.auth.SessionService
import id.primawash.api.branch.BranchRepository
import id.primawash.api.branch.BranchService
import id.primawash.api.common.AppConfig
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.SecureTokens
import id.primawash.api.common.toApi
import id.primawash.api.db.DatabaseFactory
import id.primawash.api.db.ExposedTransactionRunner
import id.primawash.api.device.DeviceRepository
import id.primawash.api.device.DeviceService
import id.primawash.api.staff.StaffRepository
import id.primawash.api.staff.StaffService
import kotlinx.coroutines.runBlocking
import java.time.Clock

/**
 * Issues a device activation code from the server, for the first tablet — before any device exists
 * that an owner could log in on (PRD §12.1). Usage:
 *
 * ```
 * ./gradlew issueActivationCode -Pbranch=TBT        # local / dev
 * bin/pwl-activation-code TBT                        # inside the API container (staging/production)
 * ```
 *
 * The code is printed to stdout for the operator to type into the tablet, never to the log. It is
 * attributed to the first active owner and audited like a code created in the app.
 */
fun main(args: Array<String>) {
    val config = AppConfig.fromEnvironment()
    val branchCode = args.firstOrNull()?.takeIf { it.isNotBlank() }
    DatabaseFactory.dataSource(config.database).use { dataSource ->
        val database = DatabaseFactory.connect(dataSource)
        val clock = Clock.systemUTC()
        val tx = ExposedTransactionRunner(database)
        val audit = AuditWriter(clock)
        val hasher = PinHasher(config.pinPepper)
        val tokens = SecureTokens()
        val branches = BranchService(tx, BranchRepository(), audit)
        val sessions = SessionService(AuthRepository(), clock)
        val staff = StaffService(tx, StaffRepository(), branches, sessions, audit, hasher, tokens)
        val devices = DeviceService(tx, DeviceRepository(), branches, staff, sessions, audit, hasher, tokens, clock)

        val issued = runBlocking { devices.createActivationCodeFromServer(branchCode) }
        println("Kode aktivasi: ${issued.code}")
        println("Berlaku sampai: ${issued.expiresAt.toApi()} (UTC)")
        println("Cabang awal perangkat: ${branchCode?.uppercase() ?: "dipilih saat login"}")
    }
}
