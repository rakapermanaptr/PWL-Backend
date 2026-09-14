package id.primawash.api.db

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

/**
 * Exposed mappings of the Flyway schema (`db/migration`). Flyway owns the DDL — these objects only
 * describe columns so repositories can query them; nothing here ever creates or alters a table.
 *
 * Column names are the snake_case database names; the camelCase JSON names live in the `XxxDto`
 * files, and repositories map between them explicitly.
 */
object BranchesTable : Table("branches") {
    val id = javaUUID("id")
    val code = varchar("code", CODE_LENGTH)
    val name = text("name")
    val address = text("address")
    val phone = text("phone")
    val hours = text("hours")
    val dailyTarget = long("daily_target")
    val active = bool("active")
    val sortOrder = integer("sort_order")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object StaffTable : Table("staff") {
    val id = javaUUID("id")
    val name = text("name")
    val shortName = text("short_name")
    val role = text("role")
    val branchId = javaUUID("branch_id").nullable()
    val pinLookup = char("pin_lookup", HASH_LENGTH)
    val pinHash = text("pin_hash")
    val pinFailedCount = integer("pin_failed_count")
    val pinLockedUntil = timestampWithTimeZone("pin_locked_until").nullable()
    val active = bool("active")
    val lastLoginAt = timestampWithTimeZone("last_login_at").nullable()
    val sortOrder = integer("sort_order")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object DevicesTable : Table("devices") {
    val id = javaUUID("id")
    val name = text("name")
    val platform = text("platform")
    val appVersion = text("app_version")
    val lastBranchId = javaUUID("last_branch_id").nullable()
    val tokenHash = char("token_hash", HASH_LENGTH)
    val activatedBy = javaUUID("activated_by").nullable()
    val activatedAt = timestampWithTimeZone("activated_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val pendingCount = integer("pending_count")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val pinLockedUntil = timestampWithTimeZone("pin_locked_until").nullable()

    override val primaryKey = PrimaryKey(id)
}

object DeviceActivationCodesTable : Table("device_activation_codes") {
    val id = javaUUID("id")
    val codeHash = char("code_hash", HASH_LENGTH)
    val createdBy = javaUUID("created_by")
    val branchId = javaUUID("branch_id").nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val usedByDevice = javaUUID("used_by_device").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object SessionsTable : Table("sessions") {
    val id = javaUUID("id")
    val deviceId = javaUUID("device_id")
    val staffId = javaUUID("staff_id")
    val branchId = javaUUID("branch_id").nullable()
    val refreshTokenHash = char("refresh_token_hash", HASH_LENGTH)
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val refreshedAt = timestampWithTimeZone("refreshed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object StaffProofsTable : Table("staff_proofs") {
    val id = javaUUID("id")
    val tokenHash = char("token_hash", HASH_LENGTH)
    val staffId = javaUUID("staff_id")
    val deviceId = javaUUID("device_id")
    val branchId = javaUUID("branch_id")
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object CustomersTable : Table("customers") {
    val id = javaUUID("id")
    val name = text("name")
    val phone = text("phone")
    val phoneDigits = varchar("phone_digits", PHONE_DIGITS_LENGTH)
    val pointsBalance = long("points_balance")
    val optIn = bool("opt_in")
    val optInAt = timestampWithTimeZone("opt_in_at").nullable()
    val optOutAt = timestampWithTimeZone("opt_out_at").nullable()
    val visits = integer("visits")
    val homeBranchId = javaUUID("home_branch_id")
    val createdByStaffId = javaUUID("created_by_staff_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object PointsLedgerTable : Table("points_ledger") {
    val id = long("id")
    val customerId = javaUUID("customer_id")
    val orderId = javaUUID("order_id").nullable()
    val type = text("type")
    val delta = long("delta")
    val balanceAfter = long("balance_after")
    val staffId = javaUUID("staff_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ServicesTable : Table("services") {
    val id = javaUUID("id")
    val category = text("category")
    val name = text("name")
    val price = long("price")
    val unit = text("unit")
    val step = decimal("step", STEP_PRECISION, STEP_SCALE)
    val active = bool("active")
    val sortOrder = integer("sort_order")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ServicePriceHistoryTable : Table("service_price_history") {
    val id = long("id")
    val serviceId = javaUUID("service_id")
    val oldPrice = long("old_price")
    val newPrice = long("new_price")
    val changedBy = javaUUID("changed_by")
    val changedAt = timestampWithTimeZone("changed_at")

    override val primaryKey = PrimaryKey(id)
}

object LoyaltyRatesTable : Table("loyalty_rates") {
    val id = javaUUID("id")
    val rupiahPerStep = long("rupiah_per_step")
    val pointsPerStep = long("points_per_step")
    val effectiveFrom = timestampWithTimeZone("effective_from")
    val createdBy = javaUUID("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object RewardsTable : Table("rewards") {
    val id = javaUUID("id")
    val name = text("name")
    val costPoints = long("cost_points")
    val valueRupiah = long("value_rupiah")
    val note = text("note")
    val minSubtotal = long("min_subtotal").nullable()
    val usedCount = long("used_count")
    val active = bool("active")
    val sortOrder = integer("sort_order")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object ShiftsTable : Table("shifts") {
    val id = javaUUID("id")
    val branchId = javaUUID("branch_id")
    val openedByStaffId = javaUUID("opened_by_staff_id")
    val openedByName = text("opened_by_name")
    val openedAt = timestampWithTimeZone("opened_at")
    val closedAt = timestampWithTimeZone("closed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object DailySalesTable : Table("daily_sales") {
    val branchId = javaUUID("branch_id")
    val businessDate = date("business_date")
    val revenue = long("revenue")
    val txCount = integer("tx_count")

    override val primaryKey = PrimaryKey(branchId, businessDate)
}

object WaMessagesTable : Table("wa_messages") {
    val id = javaUUID("id")
    val customerId = javaUUID("customer_id").nullable()
    val status = text("status")
    val errorReason = text("error_reason").nullable()

    override val primaryKey = PrimaryKey(id)
}

object AuditLogTable : Table("audit_log") {
    val id = long("id")
    val branchId = javaUUID("branch_id").nullable()
    val staffId = javaUUID("staff_id").nullable()
    val actorName = text("actor_name")
    val actionType = varchar("action_type", ACTION_TYPE_LENGTH)
    val action = text("action")
    val entityType = text("entity_type").nullable()
    val entityId = text("entity_id").nullable()
    val metadata = jsonb("metadata")
    val deviceId = javaUUID("device_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

private const val CODE_LENGTH = 5
private const val HASH_LENGTH = 64
private const val PHONE_DIGITS_LENGTH = 13
private const val STEP_PRECISION = 3
private const val STEP_SCALE = 1
private const val ACTION_TYPE_LENGTH = 40

/**
 * `jsonb` as raw JSON text. The `exposed-json` module is not a dependency; this is the one column
 * shape that needs it, and writing a `PGobject` of type `jsonb` is all Postgres requires.
 */
private class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "jsonb"

    override fun valueFromDB(value: Any): String =
        when (value) {
            is PGobject -> value.value.orEmpty()
            else -> value.toString()
        }

    override fun notNullValueToDB(value: String): Any =
        PGobject().apply {
            type = "jsonb"
            this.value = value
        }
}

private fun Table.jsonb(name: String) = registerColumn(name, JsonbColumnType())
