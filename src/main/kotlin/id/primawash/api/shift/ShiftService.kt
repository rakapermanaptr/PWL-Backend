package id.primawash.api.shift

import id.primawash.api.auth.AuthService
import id.primawash.api.auth.IssuedSession
import id.primawash.api.auth.StaffPrincipal
import id.primawash.api.branch.BranchRecord
import id.primawash.api.branch.BranchService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.AuditActor
import id.primawash.api.common.AuditEntry
import id.primawash.api.common.AuditWriter
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.common.ErrorCodes
import id.primawash.api.common.IdempotencyRequest
import id.primawash.api.common.IdempotencyStore
import id.primawash.api.common.Idempotent
import id.primawash.api.common.Money
import id.primawash.api.common.NotFoundException
import id.primawash.api.common.Page
import id.primawash.api.common.Pagination
import id.primawash.api.common.StoredResponse
import id.primawash.api.common.ValidationException
import id.primawash.api.common.WibClock
import id.primawash.api.db.TransactionRunner
import id.primawash.api.db.translatingUniqueViolation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/** A shift with the one number that is not a column: cash that should be in the drawer (§9.3). */
data class ShiftView(
    val shift: ShiftRecord,
    val expectedCash: Long,
)

data class CurrentShift(
    val shift: ShiftView?,
    val entries: List<CashEntryRecord>,
)

data class OpenedShift(
    val shift: ShiftView,
    /** Present only when the shift was opened by someone other than the caller (session handover). */
    val session: IssuedSession?,
)

data class RecordedCashEntry(
    val entry: CashEntryRecord,
    val shift: ShiftView,
)

data class ClosedShift(
    val shift: ShiftView,
    val expected: Long,
    val actual: Long,
) {
    val diff: Long get() = actual - expected
}

/** How an order's payment lands in its shift (§8.6 effects 5–6). */
data class ShiftSale(
    val orderId: UUID,
    val orderNumber: String,
    val cash: Boolean,
    val total: Long,
    val earnedPoints: Long,
    val customerName: String,
    val staffId: UUID,
)

/**
 * Shifts and the cash drawer (PRD §8.7, §9.3). One open shift per branch is enforced by the partial
 * unique index `shifts_one_open_per_branch`; every write locks the shift row, so orders, cash entries and
 * closing never interleave on the same shift.
 */
@Suppress("TooManyFunctions")
class ShiftService(
    private val tx: TransactionRunner,
    private val repository: ShiftRepository,
    private val branches: BranchService,
    private val auth: AuthService,
    private val audit: AuditWriter,
    private val idempotency: IdempotencyStore,
    private val clock: Clock,
) {
    /** `GET /shifts/current`: the latest shift of [branchId] — open or closed — with its cash entries. */
    suspend fun current(branchId: UUID): CurrentShift =
        tx {
            val shift = repository.findLatest(branchId) ?: return@tx CurrentShift(null, emptyList())
            CurrentShift(view(shift), repository.entries(shift.id))
        }

    /** `GET /shifts/latest`: the most recent shift of each branch in scope (every branch when null). */
    suspend fun latest(branchIds: Collection<UUID>?): List<ShiftView> =
        tx { views(repository.latestPerBranch(branchIds)) }

    /** `GET /shifts` (owner): history, newest first; [from]/[to] are business dates of `opened_at` (WIB). */
    suspend fun history(
        branchId: UUID?,
        from: LocalDate?,
        to: LocalDate?,
        limit: Int,
        cursor: String?,
    ): Page<ShiftView> {
        val after =
            Pagination.decode(cursor)?.let { raw ->
                val (millis, id) = raw.split("|").takeIf { it.size == 2 } ?: throw invalidCursor()
                Instant.ofEpochMilli(millis.toLongOrNull() ?: throw invalidCursor()) to
                    (runCatching { UUID.fromString(id) }.getOrNull() ?: throw invalidCursor())
            }
        val from0 = from?.let(WibClock::startOfDay)
        val until = to?.let(WibClock::endOfDay)
        return tx {
            val rows = repository.history(branchId, from0, until, after, limit + 1)
            val page = rows.take(limit)
            val next =
                if (rows.size >
                    limit
                ) {
                    page.last().let { Pagination.encode("${it.openedAt.toEpochMilli()}|${it.id}") }
                } else {
                    null
                }
            Page(views(page), next)
        }
    }

    /**
     * `POST /shifts` (PRD §8.7, `OpenShiftUseCase`). Order: branch active → no shift open (`409
     * SHIFT_ALREADY_OPEN`, also when two tablets race — the unique index decides) → opening cash > 0 →
     * a valid, unused `staffProof` from `verify-pin` on this tablet.
     *
     * The proof's staff member opens the shift. Effects, in one transaction: the shift (`counted_cash` =
     * opening cash), an `OPENING` cash entry "Modal awal shift" / "Diinput {nama}", audit `SHIFT_OPENED`
     * "Buka shift Cabang {nama} · modal awal Rp…", the proof marked used — and, when the opener is not
     * the caller, the tablet handed over to the opener (new session, `STAFF_SWITCHED`).
     */
    suspend fun open(
        principal: StaffPrincipal,
        openingCash: Long?,
        staffProof: String?,
        request: IdempotencyRequest,
        snapshot: (OpenedShift) -> StoredResponse,
    ): Idempotent<OpenedShift> =
        translatingUniqueViolation(OPEN_SHIFT_INDEX, { alreadyOpen(principal.branchName) }) {
            idempotency.execute(tx, request, snapshot) {
                val branch = requireBranch(principal.branchId)
                if (!branch.active) {
                    throw BusinessRuleException(
                        ErrorCodes.BRANCH_INACTIVE,
                        "Cabang ${branch.name} nonaktif — shift baru tidak bisa dibuka sampai owner " +
                            "mengaktifkan cabang.",
                    )
                }
                if (repository.findOpen(branch.id) != null) throw alreadyOpen(branch.name)
                if (openingCash == null || openingCash <= 0) {
                    throw BusinessRuleException(ErrorCodes.OPENING_CASH_REQUIRED, "Modal awal belum diisi.")
                }
                val opener =
                    auth.consumeStaffProof(principal, staffProof)
                        ?: throw BusinessRuleException(ErrorCodes.STAFF_PROOF_INVALID, PROOF_INVALID_MESSAGE)

                val now = clock.instant()
                val id = UUID.randomUUID()
                repository.insert(id, branch.id, opener.id, opener.name, openingCash, now)
                repository.insertEntry(
                    shiftId = id,
                    branchId = branch.id,
                    kind = CashEntryKind.OPENING,
                    label = "Modal awal shift",
                    note = "Diinput ${opener.name}",
                    amount = openingCash,
                    orderId = null,
                    staffId = opener.id,
                    at = now,
                )
                val openerActor = AuditActor.staff(opener.id, opener.shortName, opener.isOwner, principal.deviceId)
                audit.write(
                    AuditEntry(
                        branchId = branch.id,
                        actor = openerActor,
                        type = AuditActionType.SHIFT_OPENED,
                        action = "Buka shift Cabang ${branch.name} · modal awal ${Money.formatRupiah(openingCash)}",
                        entityType = ENTITY,
                        entityId = id.toString(),
                        metadata = buildJsonObject { put("openingCash", openingCash) },
                    ),
                )
                val session = if (opener.id != principal.staffId) auth.handOverDevice(principal, opener) else null
                OpenedShift(view(requireNotNull(repository.findById(id))), session)
            }
        }

    /**
     * `POST /shifts/{id}/cash-entries` (`RecordCashEntryUseCase`). Order: shift exists → the caller may
     * see its branch → shift open → label → amount > 0. Stored as "Kas masuk — …"/"Kas keluar — …" with a
     * signed amount and note "Diinput {nama}"; audit `CASH_IN`/`CASH_OUT` "Kas keluar Rp180.000 — …".
     */
    @Suppress("LongParameterList")
    suspend fun recordCashEntry(
        principal: StaffPrincipal,
        shiftId: UUID,
        cashIn: Boolean,
        label: String?,
        amount: Long?,
        request: IdempotencyRequest,
        snapshot: (RecordedCashEntry) -> StoredResponse,
    ): Idempotent<RecordedCashEntry> =
        idempotency.execute(tx, request, snapshot) {
            val shift = lockVisibleShift(principal, shiftId)
            val branch = requireBranch(shift.branchId)
            if (!shift.open) {
                throw BusinessRuleException(ErrorCodes.SHIFT_NOT_OPEN, "Shift Cabang ${branch.name} belum dibuka.")
            }
            val text = label?.trim().orEmpty()
            if (text.isEmpty()) {
                throw BusinessRuleException(ErrorCodes.CASH_LABEL_REQUIRED, "Keterangan wajib diisi untuk audit trail.")
            }
            if (amount == null ||
                amount <= 0
            ) {
                throw BusinessRuleException(ErrorCodes.AMOUNT_REQUIRED, "Jumlah belum diisi.")
            }

            val now = clock.instant()
            val entryId =
                repository.insertEntry(
                    shiftId = shift.id,
                    branchId = shift.branchId,
                    kind = if (cashIn) CashEntryKind.CASH_IN else CashEntryKind.CASH_OUT,
                    label = (if (cashIn) "Kas masuk — " else "Kas keluar — ") + text,
                    note = "Diinput ${principal.staffName}",
                    amount = if (cashIn) amount else -amount,
                    orderId = null,
                    staffId = principal.staffId,
                    at = now,
                )
            // Expected cash changed: bump the shift so delta sync sends it again.
            repository.touch(shift.id)
            audit.write(
                AuditEntry(
                    branchId = shift.branchId,
                    actor = principal.auditActor,
                    type = if (cashIn) AuditActionType.CASH_IN else AuditActionType.CASH_OUT,
                    action = "Kas ${if (cashIn) "masuk" else "keluar"} ${Money.formatRupiah(amount)} — $text",
                    entityType = "cash_entry",
                    entityId = entryId.toString(),
                    metadata =
                        buildJsonObject {
                            put("shiftId", shift.id.toString())
                            put("amount", if (cashIn) amount else -amount)
                        },
                ),
            )
            RecordedCashEntry(
                repository.entries(shift.id).first { it.id == entryId },
                view(requireNotNull(repository.findById(shift.id))),
            )
        }

    /**
     * `PUT /shifts/{id}/counted-cash`: the running count of physical cash while the cashier types
     * (client debounces ≥ 500 ms). Not audited — it is a draft; the final count is frozen and audited by
     * closing. A closed shift is `409 SHIFT_CLOSED`.
     */
    suspend fun updateCountedCash(
        principal: StaffPrincipal,
        shiftId: UUID,
        countedCash: Long,
    ): ShiftView =
        tx {
            if (countedCash < 0) throw ValidationException(NEGATIVE_COUNT_MESSAGE)
            val shift = lockVisibleShift(principal, shiftId)
            if (!shift.open) throw closed(requireBranch(shift.branchId).name)
            repository.updateCountedCash(shift.id, countedCash)
            view(requireNotNull(repository.findById(shift.id)))
        }

    /**
     * `POST /shifts/{id}/close` (`CloseShiftUseCase`). Order: shift exists → the caller may see its
     * branch → still open (`409 SHIFT_CLOSED`). Freezes the recap (§9.3: expected = cash sales + Σ
     * non-sale entries, actual = counted cash), records who closed it, audit `SHIFT_CLOSED` "Tutup shift
     * Cabang {nama} · kas cocok" or "· selisih Rp…". Offline transactions should be synced first; any that
     * arrive later are still recorded, flagged `LATE_AFTER_SHIFT_CLOSE` (§11.2).
     */
    suspend fun close(
        principal: StaffPrincipal,
        shiftId: UUID,
        countedCash: Long,
        request: IdempotencyRequest,
        snapshot: (ClosedShift) -> StoredResponse,
    ): Idempotent<ClosedShift> =
        idempotency.execute(tx, request, snapshot) {
            if (countedCash < 0) throw ValidationException(NEGATIVE_COUNT_MESSAGE)
            val shift = lockVisibleShift(principal, shiftId)
            val branch = requireBranch(shift.branchId)
            if (!shift.open) throw closed(branch.name)

            val expected = expectedCash(shift)
            repository.close(shift.id, countedCash, expected, principal.staffId, clock.instant())
            val diff = countedCash - expected
            val result = if (diff == 0L) "kas cocok" else "selisih ${Money.formatRupiah(abs(diff))}"
            audit.write(
                AuditEntry(
                    branchId = shift.branchId,
                    actor = principal.auditActor,
                    type = AuditActionType.SHIFT_CLOSED,
                    action = "Tutup shift Cabang ${branch.name} · $result",
                    entityType = ENTITY,
                    entityId = shift.id.toString(),
                    metadata =
                        buildJsonObject {
                            put("expected", expected)
                            put("actual", countedCash)
                            put("diff", diff)
                        },
                ),
            )
            ClosedShift(view(requireNotNull(repository.findById(shift.id))), expected, countedCash)
        }

    // ---- Used by OrderService and SyncService, inside their transaction ---------------------------

    /** The open shift of [branchId], locked for the order being recorded. */
    fun lockOpenShift(branchId: UUID): ShiftRecord? = repository.findOpen(branchId, forUpdate = true)

    fun lockShift(id: UUID): ShiftRecord? = repository.findById(id, forUpdate = true)

    fun lockLatestShift(branchId: UUID): ShiftRecord? = repository.findLatest(branchId, forUpdate = true)

    /**
     * An order's payment (§8.6 effects 5–6): cash or transfer sales, transaction count and points issued
     * on the shift; for cash, a `SALE` entry "Pembayaran tunai {nomor}" noting the customer. The entry is a
     * record only — its amount is already in `cash_sales` (§9.3). A fully discounted order (total 0) has
     * no cash to record.
     */
    fun recordSale(
        shift: ShiftRecord,
        sale: ShiftSale,
    ) {
        repository.addSale(
            shift.id,
            cash = if (sale.cash) sale.total else 0,
            transfer = if (sale.cash) 0 else sale.total,
            points = sale.earnedPoints,
        )
        if (sale.cash && sale.total > 0) {
            repository.insertEntry(
                shiftId = shift.id,
                branchId = shift.branchId,
                kind = CashEntryKind.SALE,
                label = "Pembayaran tunai ${sale.orderNumber}",
                note = sale.customerName,
                amount = sale.total,
                orderId = sale.orderId,
                staffId = sale.staffId,
                at = clock.instant(),
            )
        }
    }

    fun findViews(ids: Collection<UUID>): List<ShiftView> = views(repository.findByIds(ids))

    fun latestViews(branchIds: Collection<UUID>?): List<ShiftView> = views(repository.latestPerBranch(branchIds))

    // ---- Internals ------------------------------------------------------------------------------

    private fun lockVisibleShift(
        principal: StaffPrincipal,
        id: UUID,
    ): ShiftRecord {
        val shift = repository.findById(id, forUpdate = true) ?: throw NotFoundException("Shift tidak ditemukan.")
        principal.scopedBranch(shift.branchId)
        return shift
    }

    private fun expectedCash(shift: ShiftRecord): Long =
        shift.cashSales + (repository.nonSaleTotals(listOf(shift.id))[shift.id] ?: 0L)

    private fun view(shift: ShiftRecord) = ShiftView(shift, expectedCash(shift))

    private fun views(shifts: List<ShiftRecord>): List<ShiftView> {
        val nonSale = repository.nonSaleTotals(shifts.map { it.id })
        return shifts.map { ShiftView(it, it.cashSales + (nonSale[it.id] ?: 0L)) }
    }

    private fun requireBranch(id: UUID): BranchRecord = branches.find(id) ?: throw BranchService.branchNotFound()

    companion object {
        private const val ENTITY = "shift"
        private const val OPEN_SHIFT_INDEX = "shifts_one_open_per_branch"
        const val PROOF_INVALID_MESSAGE = "Verifikasi PIN sudah kedaluwarsa — pilih staff dan masukkan PIN lagi."
        private const val NEGATIVE_COUNT_MESSAGE = "Hitungan uang fisik tidak boleh negatif."

        private fun alreadyOpen(branchName: String) =
            ConflictException(
                ErrorCodes.SHIFT_ALREADY_OPEN,
                "Shift Cabang $branchName masih terbuka — tutup dulu sebelum membuka yang baru.",
            )

        private fun closed(branchName: String) =
            ConflictException(ErrorCodes.SHIFT_CLOSED, "Shift Cabang $branchName sudah ditutup.")

        private fun invalidCursor() = ValidationException("Cursor tidak valid — muat ulang daftar dari awal.")
    }
}
