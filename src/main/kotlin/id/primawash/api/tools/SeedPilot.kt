package id.primawash.api.tools

import id.primawash.api.auth.PinHasher
import id.primawash.api.common.AppConfig
import id.primawash.api.common.AppEnvironment
import id.primawash.api.db.DatabaseFactory
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/**
 * Seeds the pilot master data of PRD Lampiran D: three branches, the price list, the loyalty rate,
 * three rewards and seven staff accounts.
 *
 * dev/staging only — it refuses to run when `APP_ENV=PROD`, because in production master data is
 * created through the owner endpoints and the demo PINs below must never exist.
 *
 * Idempotent: every row is keyed on its natural key and skipped when already present, so running
 * the task twice does not duplicate anything. Orders, shifts, customers and audit rows are
 * deliberately **not** seeded — those are transactional data, not master data.
 */
private data class SeedBranch(
    val code: String,
    val name: String,
    val address: String,
    val phone: String,
    val hours: String,
    val dailyTarget: Long,
    val sortOrder: Int,
)

private data class SeedService(
    val category: String,
    val name: String,
    val price: Long,
    val unit: String,
    val active: Boolean,
    val sortOrder: Int,
) {
    val step: String get() = if (unit == "kg") "0.5" else "1.0"
}

private data class SeedReward(
    val name: String,
    val costPoints: Long,
    val valueRupiah: Long,
    val note: String,
    val minSubtotal: Long?,
    val sortOrder: Int,
)

/** PIN demo — sama dengan `DatabaseSeeder.kt` di klien. Wajib diganti sebelum go-live. */
private data class SeedStaff(
    val name: String,
    val shortName: String,
    val role: String,
    val branchCode: String?,
    val pin: String,
    val active: Boolean,
    val sortOrder: Int,
)

private val BRANCHES =
    listOf(
        SeedBranch(
            "TBT",
            "Tebet",
            "Jl. Tebet Raya No. 42, Jakarta Selatan",
            "021-8290-1147",
            "07.00 – 21.00",
            5_200_000,
            1,
        ),
        SeedBranch(
            "BTR",
            "Bintaro",
            "Jl. Bintaro Utama Sektor 3A No. 9, Tangsel",
            "021-7345-6620",
            "07.00 – 21.00",
            3_400_000,
            2,
        ),
        SeedBranch(
            "CPT",
            "Cipete",
            "Jl. Cipete Raya No. 18B, Jakarta Selatan",
            "021-7690-4432",
            "08.00 – 20.00",
            2_300_000,
            3,
        ),
    )

private val SERVICES =
    listOf(
        SeedService("KILOAN_REGULER", "Cuci Kering", 7_000, "kg", true, 1),
        SeedService("KILOAN_REGULER", "Cuci Setrika", 10_000, "kg", true, 2),
        SeedService("KILOAN_REGULER", "Setrika Saja", 6_000, "kg", true, 3),
        SeedService("KILOAN_EXPRESS", "Cuci Setrika Express 6 Jam", 18_000, "kg", true, 4),
        SeedService("KILOAN_EXPRESS", "Cuci Setrika Kilat 1 Hari", 14_000, "kg", true, 5),
        SeedService("KILOAN_EXPRESS", "Setrika Express 3 Jam", 11_000, "kg", true, 6),
        SeedService("SATUAN", "Bed Cover", 35_000, "pcs", true, 7),
        SeedService("SATUAN", "Selimut", 25_000, "pcs", true, 8),
        SeedService("SATUAN", "Jaket / Jas", 22_000, "pcs", true, 9),
        SeedService("SATUAN", "Sepatu", 35_000, "pasang", true, 10),
        SeedService("SATUAN", "Karpet", 20_000, "m²", true, 11),
        SeedService("SATUAN", "Gorden", 18_000, "kg", false, 12),
        SeedService("DRY_CLEAN", "Jas / Blazer", 45_000, "pcs", true, 13),
        SeedService("DRY_CLEAN", "Gaun / Kebaya", 55_000, "pcs", true, 14),
        SeedService("DRY_CLEAN", "Batik Halus", 38_000, "pcs", true, 15),
        SeedService("DRY_CLEAN", "Gorden Dry Clean", 30_000, "kg", true, 16),
    )

private val REWARDS =
    listOf(
        SeedReward("Diskon Rp10.000", 1_000, 10_000, "Potongan langsung di transaksi ini", null, 1),
        SeedReward("Gratis Cuci Kering 2 kg", 1_500, 14_000, "Setara Rp14.000", null, 2),
        // T9: minimum belanja sekarang menjadi kolom, bukan sekadar catatan.
        SeedReward("Diskon Rp25.000", 2_500, 25_000, "Minimum belanja Rp75.000", 75_000, 3),
    )

private val STAFF =
    listOf(
        SeedStaff("Siti Nurhaliza", "Siti N.", "KASIR", "TBT", "1234", true, 1),
        SeedStaff("Bagas Ardhana", "Bagas A.", "KASIR", "TBT", "5678", true, 2),
        SeedStaff("Nia Ramadhani", "Nia R.", "KASIR", "BTR", "2468", true, 3),
        SeedStaff("Fajar Nugroho", "Fajar N.", "KASIR", "BTR", "1357", true, 4),
        SeedStaff("Wulan Sari", "Wulan S.", "KASIR", "CPT", "3690", true, 5),
        SeedStaff("Yuni Astari", "Yuni A.", "KASIR", "CPT", "4321", false, 6),
        SeedStaff("Raka Prasetyo", "Raka", "OWNER", null, "9090", true, 7),
    )

private const val RATE_RUPIAH_PER_STEP = 10_000L
private const val RATE_POINTS_PER_STEP = 100L

private val logger = LoggerFactory.getLogger("SeedPilot")

fun main() {
    val config = AppConfig.fromEnvironment()
    check(config.environment != AppEnvironment.PROD) {
        "seedPilot tidak boleh dijalankan di produksi — master data produksi dibuat lewat endpoint owner."
    }

    DatabaseFactory.dataSource(config.database).use { dataSource ->
        DatabaseFactory.migrate(dataSource)
        val hasher = PinHasher(config.pinPepper)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val summary =
                runCatching {
                    seedPilot(connection, hasher)
                }.onFailure { connection.rollback() }.getOrThrow()
            connection.commit()
            logger.info(
                "Seed pilot selesai di {} — cabang: {}, layanan: {}, reward: {}, staff: {}, rate: {}",
                config.environment,
                summary.branches,
                summary.services,
                summary.rewards,
                summary.staff,
                summary.rates,
            )
            logger.info("PIN demo tidak dicetak ke log. Lihat docs/PRD-Backend-REST-API.md Lampiran D dan kode seed.")
        }
    }
}

data class SeedSummary(
    val branches: Int = 0,
    val services: Int = 0,
    val rewards: Int = 0,
    val staff: Int = 0,
    val rates: Int = 0,
)

/**
 * Inserts the pilot master data into an already-migrated database. Exposed separately from [main]
 * so the integration suite can run it against a Testcontainers Postgres.
 */
fun seedPilot(
    connection: Connection,
    hasher: PinHasher,
): SeedSummary =
    SeedSummary(
        branches = seedBranches(connection),
        services = seedServices(connection),
        rewards = seedRewards(connection),
        staff = seedStaff(connection, hasher),
        rates = seedLoyaltyRate(connection),
    )

private fun seedBranches(connection: Connection): Int =
    BRANCHES.count { branch ->
        if (exists(connection, "SELECT 1 FROM branches WHERE code = ?", branch.code)) return@count false
        connection
            .prepareStatement(
                """
                INSERT INTO branches (code, name, address, phone, hours, daily_target, active, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, true, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, branch.code)
                statement.setString(2, branch.name)
                statement.setString(3, branch.address)
                statement.setString(4, branch.phone)
                statement.setString(5, branch.hours)
                statement.setLong(6, branch.dailyTarget)
                statement.setInt(7, branch.sortOrder)
                statement.executeUpdate()
            }
        true
    }

private fun seedServices(connection: Connection): Int =
    SERVICES.count { service ->
        if (exists(connection, "SELECT 1 FROM services WHERE lower(name) = lower(?)", service.name)) return@count false
        connection
            .prepareStatement(
                """
                INSERT INTO services (category, name, price, unit, step, active, sort_order)
                VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, service.category)
                statement.setString(2, service.name)
                statement.setLong(3, service.price)
                statement.setString(4, service.unit)
                statement.setString(5, service.step)
                statement.setBoolean(6, service.active)
                statement.setInt(7, service.sortOrder)
                statement.executeUpdate()
            }
        true
    }

private fun seedRewards(connection: Connection): Int =
    REWARDS.count { reward ->
        if (exists(connection, "SELECT 1 FROM rewards WHERE name = ?", reward.name)) return@count false
        connection
            .prepareStatement(
                """
                INSERT INTO rewards (name, cost_points, value_rupiah, note, min_subtotal, used_count, active, sort_order)
                VALUES (?, ?, ?, ?, ?, 0, true, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, reward.name)
                statement.setLong(2, reward.costPoints)
                statement.setLong(3, reward.valueRupiah)
                statement.setString(4, reward.note)
                reward.minSubtotal?.let { statement.setLong(5, it) } ?: statement.setNull(5, Types.BIGINT)
                statement.setInt(6, reward.sortOrder)
                statement.executeUpdate()
            }
        true
    }

/**
 * The first rate is effective from the epoch, so an offline transaction with an old `capturedAt`
 * still resolves to a rate — the lookup is always "the row effective at `capturedAt`", never the
 * current one.
 */
private fun seedLoyaltyRate(connection: Connection): Int {
    if (exists(connection, "SELECT 1 FROM loyalty_rates")) return 0
    connection
        .prepareStatement(
            "INSERT INTO loyalty_rates (rupiah_per_step, points_per_step, effective_from) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, RATE_RUPIAH_PER_STEP)
            statement.setLong(2, RATE_POINTS_PER_STEP)
            statement.setTimestamp(3, Timestamp.from(Instant.EPOCH))
            statement.executeUpdate()
        }
    return 1
}

private fun seedStaff(
    connection: Connection,
    hasher: PinHasher,
): Int =
    STAFF.count { member ->
        if (exists(connection, "SELECT 1 FROM staff WHERE name = ?", member.name)) return@count false
        connection
            .prepareStatement(
                """
                INSERT INTO staff (name, short_name, role, branch_id, pin_lookup, pin_hash, active, sort_order)
                VALUES (?, ?, ?, (SELECT id FROM branches WHERE code = ?), ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, member.name)
                statement.setString(2, member.shortName)
                statement.setString(3, member.role)
                statement.setString(4, member.branchCode)
                statement.setString(5, hasher.lookup(member.pin))
                statement.setString(6, hasher.hash(member.pin))
                statement.setBoolean(7, member.active)
                statement.setInt(8, member.sortOrder)
                statement.executeUpdate()
            }
        true
    }

private fun exists(
    connection: Connection,
    sql: String,
    vararg params: String?,
): Boolean =
    connection.prepareStatement(sql).use { statement ->
        params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { it.next() }
    }
