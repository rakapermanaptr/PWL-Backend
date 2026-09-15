package id.primawash.api.shift

import id.primawash.api.auth.AuthService
import id.primawash.api.auth.IssuedSession
import id.primawash.api.branch.BranchService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.DomainException
import id.primawash.api.common.Idempotent
import id.primawash.api.common.StoredResponse
import id.primawash.api.support.BINTARO
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.TEBET
import id.primawash.api.support.TxFixtures
import id.primawash.api.support.principal
import id.primawash.api.support.recordingAudit
import id.primawash.api.support.staffRecord
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ShiftServiceTest {
    private val repository = mockk<ShiftRepository>(relaxUnitFun = true)
    private val branches = mockk<BranchService>()
    private val auth = mockk<AuthService>()
    private val audit = recordingAudit()
    private val service =
        ShiftService(
            DirectTransactionRunner,
            repository,
            branches,
            auth,
            audit.first,
            TxFixtures.freshIdempotency(),
            FIXED_CLOCK,
        )

    private val sitiStaff = staffRecord("Siti Nurhaliza")
    private val siti = principal(sitiStaff)
    private val stored: (Any?) -> StoredResponse = { StoredResponse(200, JsonNull) }

    init {
        every { branches.find(TEBET.id) } returns TEBET
        every { branches.find(BINTARO.id) } returns BINTARO
        every { repository.findOpen(TEBET.id, any()) } returns null
        every { repository.nonSaleTotals(any()) } returns emptyMap()
        every { repository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            UUID.randomUUID()
        every { auth.consumeStaffProof(siti, "sp_ok") } returns sitiStaff
        every { auth.consumeStaffProof(siti, neq("sp_ok")) } returns null
    }

    private fun <T> fresh(result: Idempotent<T>): T = result.shouldBeInstanceOf<Idempotent.Fresh<T>>().value

    private fun open(
        openingCash: Long?,
        proof: String?,
    ): OpenedShift =
        runBlocking {
            every { repository.findById(any(), any()) } answers { TxFixtures.shift().copy(id = firstArg()) }
            fresh(service.open(siti, openingCash, proof, TxFixtures.IDEMPOTENCY, stored))
        }

    @Test
    fun `should check branch, open shift, opening cash and proof in that order`() {
        every { branches.find(TEBET.id) } returns TEBET.copy(active = false)
        assertThrows<DomainException> { open(null, null) }.let {
            it.code shouldBe "BRANCH_INACTIVE"
            it.message shouldBe
                "Cabang Tebet nonaktif — shift baru tidak bisa dibuka sampai owner mengaktifkan cabang."
        }
        every { branches.find(TEBET.id) } returns TEBET

        every { repository.findOpen(TEBET.id, any()) } returns TxFixtures.shift()
        assertThrows<DomainException> { open(null, null) }.code shouldBe "SHIFT_ALREADY_OPEN"
        every { repository.findOpen(TEBET.id, any()) } returns null

        assertThrows<DomainException> { open(0, null) }.let {
            it.code shouldBe "OPENING_CASH_REQUIRED"
            it.message shouldBe "Modal awal belum diisi."
        }
        // The proof is only spent once every other rule has passed.
        verify(exactly = 0) { auth.consumeStaffProof(any(), any()) }

        assertThrows<DomainException> { open(500_000, "sp_palsu") }.code shouldBe "STAFF_PROOF_INVALID"
        verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any()) }
        audit.second shouldBe emptyList()
    }

    @Test
    fun `should open with the opening cash as the first cash entry and keep the caller's session`() {
        val opened = open(500_000, "sp_ok")

        opened.session shouldBe null
        verify { repository.insert(any(), TEBET.id, sitiStaff.id, "Siti Nurhaliza", 500_000, NOW) }
        verify {
            repository.insertEntry(
                any(),
                TEBET.id,
                CashEntryKind.OPENING,
                "Modal awal shift",
                "Diinput Siti Nurhaliza",
                500_000,
                null,
                sitiStaff.id,
                NOW,
            )
        }
        audit.second.single().action shouldBe "Buka shift Cabang Tebet · modal awal Rp500.000"
        verify(exactly = 0) { auth.handOverDevice(any(), any()) }
    }

    @Test
    fun `should hand the tablet over when someone else's proof opens the shift`() {
        val bagas = staffRecord("Bagas Ardhana")
        val session = mockk<IssuedSession>()
        every { auth.consumeStaffProof(siti, "sp_bagas") } returns bagas
        every { auth.handOverDevice(siti, bagas) } returns session

        val opened = open(400_000, "sp_bagas")

        opened.session shouldBe session
        audit.second
            .single()
            .actor.staffId shouldBe bagas.id
    }

    private fun cashEntry(
        shift: ShiftRecord,
        cashIn: Boolean,
        label: String?,
        amount: Long?,
    ): RecordedCashEntry =
        runBlocking {
            every { repository.findById(shift.id, any()) } returns shift
            every { repository.entries(shift.id) } answers { emptyList() }
            fresh(service.recordCashEntry(siti, shift.id, cashIn, label, amount, TxFixtures.IDEMPOTENCY, stored))
        }

    @Test
    fun `should validate a cash entry in the documented order`() {
        val open = TxFixtures.shift()

        assertThrows<DomainException> { cashEntry(TxFixtures.shift(branchId = BINTARO.id), false, "", 0) }.code shouldBe
            "BRANCH_SCOPE"
        assertThrows<DomainException> { cashEntry(TxFixtures.shift(closedAt = NOW), false, "", 0) }.let {
            it.code shouldBe "SHIFT_NOT_OPEN"
            it.message shouldBe "Shift Cabang Tebet belum dibuka."
        }
        assertThrows<DomainException> { cashEntry(open, false, "  ", 0) }.let {
            it.code shouldBe "CASH_LABEL_REQUIRED"
            it.message shouldBe "Keterangan wajib diisi untuk audit trail."
        }
        assertThrows<DomainException> { cashEntry(open, false, "beli deterjen", 0) }.let {
            it.code shouldBe "AMOUNT_REQUIRED"
            it.message shouldBe "Jumlah belum diisi."
        }
        assertThrows<DomainException> { cashEntry(open, false, "beli deterjen", null) }.code shouldBe "AMOUNT_REQUIRED"
        verify(exactly = 0) { repository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `should store cash out as a negative amount with the client's wording`() {
        val shift = TxFixtures.shift()
        val id = UUID.randomUUID()
        every { repository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns id
        every { repository.entries(shift.id) } returns
            listOf(
                CashEntryRecord(
                    id,
                    shift.id,
                    TEBET.id,
                    CashEntryKind.CASH_OUT,
                    "",
                    "",
                    -180_000,
                    null,
                    siti.staffId,
                    NOW,
                ),
            )
        every { repository.findById(shift.id, any()) } returns shift

        runBlocking {
            service.recordCashEntry(siti, shift.id, false, " beli deterjen ", 180_000, TxFixtures.IDEMPOTENCY, stored)
        }

        verify {
            repository.insertEntry(
                shift.id,
                TEBET.id,
                CashEntryKind.CASH_OUT,
                "Kas keluar — beli deterjen",
                "Diinput Siti Nurhaliza",
                -180_000,
                null,
                siti.staffId,
                NOW,
            )
        }
        verify { repository.touch(shift.id) }
        audit.second.single().let {
            it.type shouldBe AuditActionType.CASH_OUT
            it.action shouldBe "Kas keluar Rp180.000 — beli deterjen"
        }
    }

    private fun close(
        shift: ShiftRecord,
        counted: Long,
    ): ClosedShift =
        runBlocking {
            every { repository.findById(shift.id, any()) } returns shift
            fresh(service.close(siti, shift.id, counted, TxFixtures.IDEMPOTENCY, stored))
        }

    @Test
    fun `should freeze expected cash as cash sales plus every non-sale entry`() {
        // Scenario #11: opening 500.000 + cash in 62.000 − cash out 1.180.000 (entries), cash sales 2.242.000.
        val shift = TxFixtures.shift(cashSales = 2_242_000)
        every { repository.nonSaleTotals(listOf(shift.id)) } returns mapOf(shift.id to 500_000L + 62_000 - 1_180_000)

        val closed = close(shift, 1_624_000)

        closed.expected shouldBe 1_624_000
        closed.diff shouldBe 0
        verify { repository.close(shift.id, 1_624_000, 1_624_000, siti.staffId, NOW) }
        audit.second.single().action shouldBe "Tutup shift Cabang Tebet · kas cocok"
    }

    @Test
    fun `should state the difference without a sign and refuse a closed shift`() {
        val shift = TxFixtures.shift()
        every { repository.nonSaleTotals(listOf(shift.id)) } returns mapOf(shift.id to 500_000L)

        close(shift, 492_000).diff shouldBe -8_000
        audit.second.single().action shouldBe "Tutup shift Cabang Tebet · selisih Rp8.000"

        assertThrows<DomainException> { close(TxFixtures.shift(closedAt = NOW), 0) }.let {
            it.code shouldBe "SHIFT_CLOSED"
            it.message shouldBe "Shift Cabang Tebet sudah ditutup."
        }
    }

    @Test
    fun `should add the payment to the shift and a SALE entry only for cash that changed hands`() {
        val shift = TxFixtures.shift()

        fun sale(
            cash: Boolean,
            total: Long,
        ) = ShiftSale(UUID.randomUUID(), "TBT-0915-001", cash, total, 300, "Dewi Anggraini", siti.staffId)

        service.recordSale(shift, sale(cash = true, total = 31_000))
        service.recordSale(shift, sale(cash = false, total = 45_000))
        service.recordSale(shift, sale(cash = true, total = 0))

        verify { repository.addSale(shift.id, cash = 31_000, transfer = 0, points = 300) }
        verify { repository.addSale(shift.id, cash = 0, transfer = 45_000, points = 300) }
        verify(exactly = 1) {
            repository.insertEntry(
                shift.id,
                TEBET.id,
                CashEntryKind.SALE,
                "Pembayaran tunai TBT-0915-001",
                "Dewi Anggraini",
                31_000,
                any(),
                siti.staffId,
                NOW,
            )
        }
        verify(exactly = 1) { repository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
