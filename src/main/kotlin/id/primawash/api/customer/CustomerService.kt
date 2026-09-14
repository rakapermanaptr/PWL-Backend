package id.primawash.api.customer

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.Page
import id.primawash.api.common.Pagination
import id.primawash.api.common.PhoneNumber
import id.primawash.api.common.ValidationException
import id.primawash.api.db.TransactionRunner
import id.primawash.api.db.isUniqueViolation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.util.UUID

data class CustomerUpdate(
    val customer: CustomerRecord,
    val changed: Boolean,
)

/**
 * Customers are shared by every branch: one phone number is one customer, and points are valid at
 * any branch (PRD §8.5). Points are never edited here — only the ledger moves them (M2/M3).
 */
class CustomerService(
    private val tx: TransactionRunner,
    private val repository: CustomerRepository,
    private val audit: AuditWriter,
    private val clock: Clock,
) {
    /** `GET /customers?q=`: most visits first without a query; name or phone digits with one. */
    suspend fun search(
        query: String?,
        limit: Int,
        cursor: String?,
    ): Page<CustomerRecord> {
        val offset = Pagination.decodeOffset(cursor)
        val rows = tx { repository.search(query, limit + 1, offset) }
        val next = if (rows.size > limit) Pagination.encodeOffset(offset + limit) else null
        return Page(rows.take(limit), next)
    }

    suspend fun get(id: UUID): CustomerRecord = tx { repository.findById(id) } ?: throw customerNotFound()

    /**
     * `POST /customers` (`RegisterCustomerUseCase`): name required → valid WhatsApp number → number not
     * registered at any branch (`409 PHONE_ALREADY_REGISTERED`, with the existing customer so the
     * cashier can pick it). Stored with 0 points, 0 visits, the token branch as home branch and the
     * consent timestamp when opted in. The `OPT_IN_CONFIRM` message goes out with the customer's first
     * transaction (§10.2, M2), not here.
     */
    suspend fun register(
        clientId: UUID?,
        name: String?,
        phone: String?,
        optIn: Boolean,
        branchId: UUID,
        actor: AuditActor,
    ): CustomerRecord {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isEmpty()) throw BusinessRuleException(ErrorCodes.NAME_REQUIRED, "Nama customer wajib diisi.")
        val digits =
            PhoneNumber.validate(phone.orEmpty()).getOrElse {
                throw BusinessRuleException(
                    ErrorCodes.PHONE_INVALID,
                    "Nomor WhatsApp belum valid (minimal 10 digit, diawali 08).",
                )
            }
        return try {
            tx {
                repository.findByPhoneDigits(digits)?.let { throw phoneTaken(it) }
                val id = clientId ?: UUID.randomUUID()
                if (clientId != null && repository.findById(clientId) != null) {
                    throw ValidationException("ID customer dari perangkat sudah dipakai customer lain.")
                }
                val display = PhoneNumber.display(digits)
                repository.insert(
                    id,
                    cleanName,
                    display,
                    digits,
                    optIn,
                    branchId,
                    requireNotNull(actor.staffId),
                    clock.instant(),
                )
                audit.write(
                    AuditEntry(
                        branchId = branchId,
                        actor = actor,
                        type = AuditActionType.CUSTOMER_REGISTERED,
                        action = "Daftarkan customer $cleanName ($display) · opt-in WA: ${yesOrNotYet(optIn)}",
                        entityType = ENTITY,
                        entityId = id.toString(),
                        metadata = buildJsonObject { put("optIn", optIn) },
                    ),
                )
                requireNotNull(repository.findById(id))
            }
        } catch (error: Exception) {
            if (isUniqueViolation(error, PHONE_INDEX)) {
                tx { repository.findByPhoneDigits(digits) }?.let { throw phoneTaken(it) }
            }
            throw error
        }
    }

    /**
     * `PATCH /customers/{id}`: rename and/or change WhatsApp consent. Opting out also cancels any
     * message still queued for the customer, in the same transaction — consent is checked when a
     * message is written and again here, never assumed from an order's stamped `waStatus` (T7).
     */
    suspend fun update(
        id: UUID,
        name: String?,
        optIn: Boolean?,
        branchId: UUID,
        actor: AuditActor,
    ): CustomerUpdate {
        val cleanName = name?.trim()
        if (cleanName != null && cleanName.isEmpty()) {
            throw BusinessRuleException(ErrorCodes.NAME_REQUIRED, "Nama customer wajib diisi.")
        }
        return tx {
            val customer = repository.findById(id, forUpdate = true) ?: throw customerNotFound()
            val now = clock.instant()
            var changed = false
            if (cleanName != null && cleanName != customer.name) {
                repository.updateName(id, cleanName)
                audit.write(
                    entry(
                        branchId,
                        actor,
                        AuditActionType.CUSTOMER_UPDATED,
                        "Ubah nama customer ${customer.name} → $cleanName",
                        id,
                    ),
                )
                changed = true
            }
            val currentName = cleanName ?: customer.name
            if (optIn != null && optIn != customer.optIn) {
                repository.updateOptIn(id, optIn, now)
                if (!optIn) repository.cancelQueuedWhatsApp(id, "Customer berhenti berlangganan WA")
                audit.write(
                    entry(
                        branchId,
                        actor,
                        if (optIn) AuditActionType.CUSTOMER_UPDATED else AuditActionType.CUSTOMER_OPTED_OUT,
                        "Ubah opt-in WA customer $currentName: ${yesOrNotYet(optIn)}",
                        id,
                    ),
                )
                changed = true
            }
            CustomerUpdate(requireNotNull(repository.findById(id)), changed)
        }
    }

    /** `GET /customers/{id}/points`: the ledger that the cached balance is derived from, newest first. */
    suspend fun ledger(
        id: UUID,
        limit: Int,
        cursor: String?,
    ): Page<PointsEntryRecord> {
        val before =
            Pagination.decode(cursor)?.let {
                it.toLongOrNull() ?: throw ValidationException("Cursor tidak valid — muat ulang daftar dari awal.")
            }
        val rows =
            tx {
                repository.findById(id) ?: throw customerNotFound()
                repository.ledger(id, limit + 1, before)
            }
        val page = rows.take(limit)
        val next = if (rows.size > limit) Pagination.encode(page.last().id.toString()) else null
        return Page(page, next)
    }

    private fun entry(
        branchId: UUID,
        actor: AuditActor,
        type: AuditActionType,
        action: String,
        customerId: UUID,
    ) = AuditEntry(branchId, actor, type, action, ENTITY, customerId.toString())

    companion object {
        private const val ENTITY = "customer"
        private const val PHONE_INDEX = "customers_phone_digits_key"

        private fun yesOrNotYet(optIn: Boolean) = if (optIn) "ya" else "belum"

        private fun customerNotFound() = NotFoundException("Customer tidak ditemukan.")

        /** `details.customer` lets the client jump straight to the customer who owns the number. */
        private fun phoneTaken(existing: CustomerRecord) =
            ConflictException(
                ErrorCodes.PHONE_ALREADY_REGISTERED,
                "Nomor ini sudah terdaftar (bisa dari cabang lain) — pakai pencarian untuk memilihnya.",
                buildJsonObject { put("customer", existing.toDetails()) },
            )

        private fun CustomerRecord.toDetails(): JsonObject =
            buildJsonObject {
                put("id", id.toString())
                put("name", name)
                put("phone", phone)
                put("points", points)
                put("optIn", optIn)
                put("visits", visits)
                put("homeBranchId", homeBranchId.toString())
            }
    }
}
