package id.primawash.api.order

import id.primawash.api.branch.BranchService
import id.primawash.api.catalog.CatalogService
import id.primawash.api.catalog.LoyaltyRateRecord
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.IdempotencyStore
import id.primawash.api.common.Idempotent
import id.primawash.api.common.StoredResponse
import id.primawash.api.common.ValidationException
import id.primawash.api.customer.CustomerService
import id.primawash.api.customer.OfflineCustomer
import id.primawash.api.shift.ShiftRecord
import id.primawash.api.shift.ShiftService
import id.primawash.api.staff.StaffService
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.TEBET
import id.primawash.api.support.TxFixtures
import id.primawash.api.support.TxFixtures.CUCI_SETRIKA
import id.primawash.api.support.TxFixtures.GORDEN
import id.primawash.api.support.principal
import id.primawash.api.support.recordingAudit
import id.primawash.api.support.staffRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OrderSyncServiceTest {
    private val repository = mockk<OrderRepository>()
    private val sales = mockk<SaleRecorder>()
    private val branches = mockk<BranchService>()
    private val catalog = mockk<CatalogService>()
    private val customers = mockk<CustomerService>()
    private val shifts = mockk<ShiftService>()
    private val staff = mockk<StaffService>()
    private val audit = recordingAudit()
    private val idempotency = mockk<IdempotencyStore>(relaxUnitFun = true)
    private val service =
        OrderSyncService(
            DirectTransactionRunner,
            repository,
            sales,
            branches,
            catalog,
            customers,
            shifts,
            staff,
            audit.first,
            idempotency,
            FIXED_CLOCK,
        )

    private val siti = principal(staffRecord("Siti Nurhaliza"))
    private val openShift = TxFixtures.shift()
    private val drafts = mutableListOf<SaleDraft>()
    private val capturedAt: Instant = NOW.minus(Duration.ofHours(2))

    init {
        every { idempotency.find(any()) } returns null
        every { branches.find(TEBET.id) } returns TEBET
        every { repository.findByClientTxId(any()) } returns null
        every { catalog.findServices(any()) } answers {
            listOf(CUCI_SETRIKA, GORDEN).filter { it.id in firstArg<Collection<UUID>>() }.associateBy { it.id }
        }
        every { catalog.priceAt(any(), any()) } answers { firstArg<id.primawash.api.catalog.ServiceRecord>().price }
        every { catalog.rateAt(any()) } returns TxFixtures.RATE
        every { shifts.lockShift(openShift.id) } returns openShift
        every { shifts.lockOpenShift(TEBET.id) } returns openShift
        every { sales.record(any()) } answers {
            val draft = firstArg<SaleDraft>()
            drafts += draft
            val order =
                TxFixtures.order().copy(
                    number = "TBT-0915-00${drafts.size}",
                    clientTxId = draft.clientTxId,
                    source = OrderSource.OFFLINE_SYNC,
                    total = draft.totals.total,
                    earnedPoints = draft.totals.earnedPoints,
                    shiftId = draft.shift.id,
                    capturedAt = draft.capturedAt,
                    flags = draft.flags,
                )
            RecordedSale(order, draft.customer, whatsAppQueued = false)
        }
    }

    private fun tx(
        items: List<SyncItem> = listOf(SyncItem(CUCI_SETRIKA.id, BigDecimal("2"), 10_000)),
        at: Instant = capturedAt,
        shiftId: UUID? = openShift.id,
        hasReward: Boolean = false,
    ) = SyncTransaction(UUID.randomUUID(), at, shiftId, null, null, null, items, hasReward, PaymentMethod.TUNAI, "")

    private fun sync(vararg transactions: SyncTransaction): SyncOutcome =
        runBlocking {
            val result =
                service.sync(
                    siti,
                    transactions.toList(),
                    TxFixtures.IDEMPOTENCY,
                ) { StoredResponse(200, JsonNull) }
            result.shouldBeInstanceOf<Idempotent.Fresh<SyncOutcome>>().value
        }

    @Test
    fun `should process each transaction on its own and summarise what was recorded`() {
        val outcome = sync(tx(), tx(items = listOf(SyncItem(UUID.randomUUID(), BigDecimal.ONE, 5_000))), tx())

        outcome.results.map { it.status } shouldContainExactly
            listOf(SyncStatus.CREATED, SyncStatus.REJECTED, SyncStatus.CREATED)
        outcome.results[1].error!!.code shouldBe "INVALID_ITEM"
        outcome.createdCount shouldBe 2
        outcome.rejectedCount shouldBe 1
        outcome.total shouldBe 40_000
        audit.second.last().action shouldBe
            "Sync 2 transaksi offline Cabang Tebet · Rp40.000 · ID TBT-0915-001–TBT-0915-002"
        audit.second.count { it.type == AuditActionType.ORDER_SYNCED } shouldBe 3
        verify { idempotency.save(TxFixtures.IDEMPOTENCY, any()) }
    }

    @Test
    fun `should send back only a clock ahead of the server, a redemption or an empty cart`() {
        val outcome =
            sync(
                tx(at = NOW.plus(Duration.ofMinutes(6))),
                tx(at = NOW.plus(Duration.ofMinutes(4))),
                tx(hasReward = true),
                tx(items = emptyList()),
                tx(items = listOf(SyncItem(CUCI_SETRIKA.id, BigDecimal("2.3"), 10_000))),
            )

        outcome.results.map { it.error?.code } shouldContainExactly
            listOf("CAPTURED_IN_FUTURE", null, "REDEEM_OFFLINE", "EMPTY_CART", "INVALID_ITEM")
        outcome.results[2].error!!.message shouldBe
            "Redeem poin butuh koneksi — batalkan redemption atau tunggu online."
    }

    @Test
    fun `should keep the price the customer paid and flag what differs from the list at capture time`() {
        every { catalog.priceAt(CUCI_SETRIKA, capturedAt) } returns 11_000

        val order =
            sync(
                tx(
                    items =
                        listOf(
                            SyncItem(CUCI_SETRIKA.id, BigDecimal("2"), 10_000),
                            SyncItem(GORDEN.id, BigDecimal("1"), 18_000),
                        ),
                ),
            ).results
                .single()
                .order!!

        drafts.single().items.map { it.unitPrice } shouldContainExactly listOf(10_000L, 18_000L)
        drafts.single().totals.total shouldBe 38_000
        order.flags shouldContainExactly listOf(OrderFlag.PRICE_MISMATCH, OrderFlag.SERVICE_INACTIVE)
        audit.second.first().action shouldBe
            "Transaksi TBT-0915-001 · Rp38.000 · Tunai · offline · ditandai: harga beda dengan price list, " +
            "layanan sudah nonaktif"
    }

    @Test
    fun `should earn points at the rate in force when the transaction was captured`() {
        val oldRate = LoyaltyRateRecord(UUID.randomUUID(), 10_000, 100, Instant.parse("2026-01-01T00:00:00Z"))
        every { catalog.rateAt(capturedAt) } returns oldRate

        sync(tx())

        drafts.single().rate shouldBe oldRate
        drafts.single().capturedAt shouldBe capturedAt
    }

    @Test
    fun `should flag a capture older than seven days but still record it`() {
        val old = NOW.minus(Duration.ofDays(8))

        sync(tx(at = old))
            .results
            .single()
            .order!!
            .flags shouldContainExactly listOf(OrderFlag.STALE_CAPTURE)
    }

    private fun closed(): ShiftRecord = TxFixtures.shift(closedAt = NOW.minusSeconds(600))

    @Test
    fun `should put the money in its own shift, the open shift, or flag it late - never reject it`() {
        val closedShift = closed()
        every { shifts.lockShift(closedShift.id) } returns closedShift

        // Own shift closed, another open: lands in the open one, unflagged.
        sync(tx(shiftId = closedShift.id)).results.single().order!!.let {
            it.shiftId shouldBe openShift.id
            it.flags shouldBe emptyList()
        }

        // Nothing open: back to its own closed shift, flagged.
        every { shifts.lockOpenShift(TEBET.id) } returns null
        sync(tx(shiftId = closedShift.id)).results.single().order!!.let {
            it.shiftId shouldBe closedShift.id
            it.flags shouldContainExactly listOf(OrderFlag.LATE_AFTER_SHIFT_CLOSE)
        }

        // No shift reported: the branch's latest.
        val latest = closed()
        every { shifts.lockLatestShift(TEBET.id) } returns latest
        sync(tx(shiftId = null))
            .results
            .single()
            .order!!
            .shiftId shouldBe latest.id

        // A branch that never had a shift has nowhere to put the money.
        every { shifts.lockLatestShift(TEBET.id) } returns null
        sync(tx(shiftId = null))
            .results
            .single()
            .error!!
            .code shouldBe "SHIFT_NOT_OPEN"
    }

    @Test
    fun `should ignore a reported shift of another branch`() {
        val foreign = TxFixtures.shift(branchId = UUID.randomUUID())
        every { shifts.lockShift(foreign.id) } returns foreign

        sync(tx(shiftId = foreign.id))
            .results
            .single()
            .order!!
            .shiftId shouldBe openShift.id
    }

    @Test
    fun `should answer a recorded transaction as duplicate and a malformed one as rejected`() {
        val existing = TxFixtures.order()
        every { repository.findByClientTxId(existing.clientTxId) } returns existing
        val malformed =
            SyncTransaction(
                null,
                null,
                null,
                null,
                null,
                null,
                emptyList(),
                false,
                null,
                null,
                ValidationException("x"),
            )

        val outcome = sync(tx().copy(clientTxId = existing.clientTxId), malformed)

        outcome.results.map { it.status } shouldContainExactly listOf(SyncStatus.DUPLICATE, SyncStatus.REJECTED)
        outcome.results[0].order shouldBe existing
        verify(exactly = 0) { sales.record(any()) }
        // Nothing created: no summary audit.
        audit.second shouldBe emptyList()
    }

    @Test
    fun `should map a customer registered offline to the customer who already has that phone`() {
        val dewi = TxFixtures.customer()
        val tabletId = UUID.randomUUID()
        every { customers.resolveOffline(tabletId, "Dewi", "0812-3390-4471", true, TEBET.id, any()) } returns
            OfflineCustomer(dewi, matchedByPhone = true)

        val result =
            sync(tx().copy(newCustomer = NewOfflineCustomer(tabletId, "Dewi", "0812-3390-4471", optIn = true)))
                .results
                .single()

        result.customerIdMapping shouldBe (tabletId to dewi.id)
        result.order!!.flags shouldContainExactly listOf(OrderFlag.CUSTOMER_PHONE_MATCHED)
        drafts.single().customer shouldBe dewi
        drafts.single().totals.earnedPoints shouldBe 200
    }

    @Test
    fun `should replay a retried batch without processing it again`() {
        val stored = StoredResponse(200, JsonNull)
        every { idempotency.find(TxFixtures.IDEMPOTENCY) } returns stored

        val result = runBlocking { service.sync(siti, listOf(tx()), TxFixtures.IDEMPOTENCY) { stored } }

        result shouldBe Idempotent.Replayed(stored)
        verify(exactly = 0) { sales.record(any()) }
    }
}
