package id.primawash.api.support

import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.branch.BranchRecord
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.db.TransactionRunner
import id.primawash.api.device.DeviceRecord
import id.primawash.api.staff.Role
import id.primawash.api.staff.StaffRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Business rules run without a database: the "transaction" just runs the block. */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> invoke(block: () -> T): T = block()
}

val NOW: Instant = Instant.parse("2026-09-15T02:00:00Z")
val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

val FAMILIA_URBAN =
    BranchRecord(
        UUID.randomUUID(),
        "FMU",
        "Familia Urban",
        "Ruko Arundaya, Jl. Familia Urban Blok DD. 21",
        "021-8290-1147",
        "07.00 – 21.00",
        5_200_000,
        true,
    )
val NAROGONG =
    BranchRecord(
        UUID.randomUUID(),
        "NRG",
        "Narogong",
        "Jl. Narogong Indah No.12 Blok C 8, RT.005/RW.012",
        "021-7345-6620",
        "07.00 – 21.00",
        3_400_000,
        true,
    )

fun staffRecord(
    name: String,
    role: Role = Role.KASIR,
    branchId: UUID? = FAMILIA_URBAN.id,
    active: Boolean = true,
    pinLookup: String = "lookup-$name",
    failedCount: Int = 0,
    lockedUntil: Instant? = null,
) = StaffRecord(
    id = UUID.randomUUID(),
    name = name,
    shortName = name.split(" ").let { if (it.size > 1) "${it[0]} ${it[1].first()}." else it[0] },
    role = role,
    branchId = if (role == Role.OWNER) null else branchId,
    pinLookup = pinLookup,
    pinHash = "hash-$name",
    pinFailedCount = failedCount,
    pinLockedUntil = lockedUntil,
    active = active,
    lastLoginAt = null,
)

fun deviceRecord(
    name: String = "Tablet Cabang Familia Urban",
    lockedUntil: Instant? = null,
    lastBranchId: UUID? = FAMILIA_URBAN.id,
) = DeviceRecord(UUID.randomUUID(), name, "ANDROID", "1.4.0", lastBranchId, NOW, null, 0, null, lockedUntil)

val OWNER_ACTOR = AuditActor(UUID.randomUUID(), "Raka (owner)", UUID.randomUUID())

fun principal(
    staff: StaffRecord,
    branch: BranchRecord = FAMILIA_URBAN,
    deviceId: UUID = UUID.randomUUID(),
) = StaffPrincipal(
    UUID.randomUUID(),
    staff.id,
    staff.name,
    staff.shortName,
    staff.role,
    branch.id,
    branch.name,
    deviceId,
)

/** An AuditWriter that records entries instead of inserting them. */
fun recordingAudit(): Pair<AuditWriter, MutableList<AuditEntry>> {
    val entries = mutableListOf<AuditEntry>()
    val writer = mockk<AuditWriter>()
    val captured = slot<AuditEntry>()
    every { writer.write(capture(captured)) } answers { entries += captured.captured }
    return writer to entries
}
