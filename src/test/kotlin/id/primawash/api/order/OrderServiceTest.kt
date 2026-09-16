package id.primawash.api.order

import id.primawash.api.branch.BranchService
import id.primawash.api.catalog.CatalogService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.DomainException
import id.primawash.api.common.ForbiddenException
import id.primawash.api.common.Idempotent
import id.primawash.api.common.StoredResponse
import id.primawash.api.customer.CustomerService
import id.primawash.api.shift.ShiftService
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FAMILIA_URBAN
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NAROGONG
import id.primawash.api.support.NOW
import id.primawash.api.support.TxFixtures
import id.primawash.api.support.TxFixtures.BED_COVER
import id.primawash.api.support.TxFixtures.CUCI_SETRIKA
import id.primawash.api.support.TxFixtures.DISKON_25
import id.primawash.api.support.TxFixtures.GORDEN
import id.primawash.api.support.TxFixtures.GRATIS_CUCI_KERING
import id.primawash.api.support.TxFixtures.RATE
import id.primawash.api.support.principal
import id.primawash.api.support.recordingAudit
import id.primawash.api.support.staffRecord
import id.primawash.api.wa.WaService
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

class OrderServiceTest {
    private val repository = mockk<OrderRepository>(relaxUnitFun = true)
    private val sales = mockk<SaleRecorder>()
    private val branches = mockk<BranchService>()
    private val catalog = mockk<CatalogService>()
    private val customers = mockk<CustomerService>()
    private val shifts = mockk<ShiftService>()
    private val whatsApp = mockk<WaService>()
    private val audit = recordingAudit()
    private val service =
        OrderService(
            DirectTransactionRunner,
            repository,
            sales,
            branches,
            catalog,
            customers,
            shifts,
            whatsApp,
            audit.first,
            TxFixtures.freshIdempotency(),
            FIXED_CLOCK,
        )

    private val siti = principal(staffRecord("Siti Nurhaliza"))
    private val shift = TxFixtures.shift()
    private val dewi = TxFixtures.customer()
    private val drafts = slot<SaleDraft>()

    init {
        every { branches.find(FAMILIA_URBAN.id) } returns FAMILIA_URBAN
        every { repository.findByClientTxId(any()) } returns null
        every { shifts.lockOpenShift(FAMILIA_URBAN.id) } returns shift
        every { catalog.findServices(any()) } answers {
            listOf(
                CUCI_SETRIKA,
                BED_COVER,
                GORDEN,
            ).filter { it.id in firstArg<Collection<UUID>>() }.associateBy { it.id }
        }
        every { catalog.findRewards(any()) } answers {
            listOf(GRATIS_CUCI_KERING, DISKON_25).filter { it.id in firstArg<Collection<UUID>>() }
        }
        every { catalog.rateAt(NOW) } returns RATE
        every { customers.lockForOrder(dewi.id) } returns dewi
        every { sales.record(capture(drafts)) } answers {
            val draft = drafts.captured
            val order =
                TxFixtures.order(customer = draft.customer).copy(
                    subtotal = draft.totals.subtotal,
                    discount = draft.totals.discount,
                    total = draft.totals.total,
                    earnedPoints = draft.totals.earnedPoints,
                    redeemedPoints = draft.reward?.cost ?: 0,
                    rewardId = draft.reward?.id,
                    rewardName = draft.reward?.name,
                )
            RecordedSale(order, draft.customer, whatsAppQueued = false)
        }
    }

    private fun command(
        items: List<CartItem> = listOf(CartItem(CUCI_SETRIKA.id, BigDecimal("4.5"), 10_000)),
        customerId: UUID? = dewi.id,
        rewardId: UUID? = GRATIS_CUCI_KERING.id,
        expectedTotal: Long = 31_000,
        clientTxId: UUID = UUID.randomUUID(),
    ) = PlaceOrderCommand(
        clientTxId,
        customerId,
        items,
        rewardId,
        PaymentMethod.TUNAI,
        " Pisahkan baju putih ",
        expectedTotal,
    )

    private fun place(command: PlaceOrderCommand): PlacedOrder =
        runBlocking {
            val result = service.place(siti, command, TxFixtures.IDEMPOTENCY) { StoredResponse(201, JsonNull) }
            result.shouldBeInstanceOf<Idempotent.Fresh<PlacedOrder>>().value
        }

    private fun failure(command: PlaceOrderCommand): DomainException = assertThrows<DomainException> { place(command) }

    @Test
    fun `should record the server's own totals, rate and capture time`() {
        val placed = place(command())

        val draft = drafts.captured
        draft.totals shouldBe OrderTotals(subtotal = 45_000, discount = 14_000, total = 31_000, earnedPoints = 300)
        draft.capturedAt shouldBe NOW
        draft.rate shouldBe RATE
        draft.shift shouldBe shift
        draft.source shouldBe OrderSource.ONLINE
        draft.note shouldBe "Pisahkan baju putih"
        draft.flags shouldBe emptyList()
        draft.items.single().qty shouldBe BigDecimal("4.5")
        draft.items.single().subtotal shouldBe 45_000
        placed.order.total shouldBe 31_000
        audit.second.map { it.type } shouldContainExactly
            listOf(AuditActionType.ORDER_CREATED, AuditActionType.REWARD_REDEEMED)
        audit.second.first().action shouldBe "Transaksi FMU-0915-001 · Rp31.000 · Tunai · redeem 1.500 poin"
    }

    @Test
    fun `should check the cart in the documented order, first failure wins`() {
        val unknown = UUID.randomUUID()
        val everythingWrong =
            command(
                items = listOf(CartItem(GORDEN.id, BigDecimal("4.3"), 1)),
                customerId = null,
                rewardId = DISKON_25.id,
                expectedTotal = 1,
            )

        every { branches.find(FAMILIA_URBAN.id) } returns null
        failure(everythingWrong).message shouldBe "Cabang tidak ditemukan."
        every { branches.find(FAMILIA_URBAN.id) } returns FAMILIA_URBAN

        every { shifts.lockOpenShift(FAMILIA_URBAN.id) } returns null
        failure(everythingWrong).let {
            it.code shouldBe "SHIFT_NOT_OPEN"
            it.message shouldBe "Shift Cabang Familia Urban belum dibuka — buka shift dulu sebelum mencatat transaksi."
        }
        failure(command(items = emptyList())).code shouldBe "SHIFT_NOT_OPEN"
        every { shifts.lockOpenShift(FAMILIA_URBAN.id) } returns shift

        failure(everythingWrong.copy(items = emptyList())).let {
            it.code shouldBe "EMPTY_CART"
            it.message shouldBe "Belum ada layanan di order ini."
        }
        failure(everythingWrong).message shouldBe "Layanan Gorden sudah tidak tersedia."
        failure(everythingWrong.copy(items = listOf(CartItem(unknown, BigDecimal.ONE, 1)))).message shouldBe
            "Layanan yang dipilih sudah tidak tersedia."
        val badQty = everythingWrong.copy(items = listOf(CartItem(CUCI_SETRIKA.id, BigDecimal("4.3"), 1)))
        failure(badQty).let {
            it.code shouldBe "INVALID_ITEM"
            it.message shouldBe "Jumlah Cuci Setrika tidak valid."
        }
        failure(badQty.copy(items = listOf(CartItem(CUCI_SETRIKA.id, BigDecimal("1000"), 1)))).code shouldBe
            "INVALID_ITEM"

        val repriced = badQty.copy(items = listOf(CartItem(CUCI_SETRIKA.id, BigDecimal("7"), 9_000)))
        failure(repriced).let {
            it.code shouldBe "PRICE_CHANGED"
            it.message shouldBe "Harga Cuci Setrika baru saja diubah owner — cek ulang total sebelum bayar."
            val prices =
                it.details!!
                    .getValue("services")
                    .jsonArray
                    .map { s -> s.jsonObject }
            prices
                .single()
                .getValue("price")
                .jsonPrimitive.content shouldBe "10000"
        }

        val priced = repriced.copy(items = listOf(CartItem(CUCI_SETRIKA.id, BigDecimal("7"), 10_000)))
        failure(priced).let {
            it.code shouldBe "INSUFFICIENT_POINTS"
            it.message shouldBe "Saldo poin belum cukup untuk reward ini."
        }
        failure(priced.copy(customerId = dewi.id)).code shouldBe "INSUFFICIENT_POINTS"
        every { customers.lockForOrder(dewi.id) } returns dewi.copy(points = 5_000)
        failure(priced.copy(customerId = dewi.id)).let {
            it.code shouldBe "REWARD_NOT_ELIGIBLE"
            it.message shouldBe "Reward ini butuh minimum belanja Rp75.000."
        }

        failure(priced.copy(customerId = dewi.id, rewardId = GRATIS_CUCI_KERING.id)).let {
            it.code shouldBe "TOTAL_MISMATCH"
            it.message shouldBe "Total berubah — muat ulang keranjang."
            it.details!!
                .getValue("total")
                .jsonPrimitive.content shouldBe "56000"
        }
        verify(exactly = 0) { sales.record(any()) }
        audit.second shouldBe emptyList()
    }

    @Test
    fun `should refuse a reward that is no longer offered`() {
        every { catalog.findRewards(any()) } returns listOf(GRATIS_CUCI_KERING.copy(active = false))

        failure(command()).let {
            it.code shouldBe "REWARD_NOT_ELIGIBLE"
            it.message shouldBe "Reward ini sudah tidak tersedia."
        }
    }

    @Test
    fun `should never earn points for a walk-in`() {
        place(command(customerId = null, rewardId = null, expectedTotal = 45_000))

        drafts.captured.totals.earnedPoints shouldBe 0
        drafts.captured.customer shouldBe null
        audit.second.map { it.type } shouldContainExactly listOf(AuditActionType.ORDER_CREATED)
    }

    @Test
    fun `should answer a transaction id that already has an order with that order`() {
        val existing = TxFixtures.order()
        every { repository.findByClientTxId(existing.clientTxId) } returns existing

        failure(command(clientTxId = existing.clientTxId)).let {
            it.code shouldBe "DUPLICATE_TRANSACTION"
            it.details!!
                .getValue("order")
                .jsonObject
                .getValue("number")
                .jsonPrimitive.content shouldBe "FMU-0915-001"
        }
        verify(exactly = 0) { sales.record(any()) }
    }

    // ---- Advance ---------------------------------------------------------------------------------

    private fun advance(
        order: OrderRecord,
        from: OrderStatus,
        who: id.primawash.api.auth.StaffPrincipal = siti,
    ): AdvancedOrder {
        every { repository.findById(order.id, any()) } returns order
        return runBlocking { service.advance(who, order.id, from) }
    }

    private fun wantsReady(customer: id.primawash.api.customer.CustomerRecord?) {
        every { whatsApp.queueReadyForPickup(any(), any(), any(), any(), any(), any()) } answers {
            thirdArg<id.primawash.api.wa.WaRecipient>().optIn
        }
        customer?.let { every { customers.lockForOrder(it.id) } returns it }
    }

    @Test
    fun `should refuse a status another tablet already changed`() {
        val order = TxFixtures.order(status = OrderStatus.PROSES)

        val error = assertThrows<DomainException> { advance(order, OrderStatus.DITERIMA) }

        error.code shouldBe "STATUS_CHANGED"
        error.message shouldBe "Status order sudah diubah dari perangkat lain."
        error.details!!
            .getValue("order")
            .jsonObject
            .getValue("status")
            .jsonPrimitive.content shouldBe "PROSES"
        verify(exactly = 0) { repository.updateStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `should answer a completed order without changing it`() {
        val done = advance(TxFixtures.order(status = OrderStatus.SELESAI), OrderStatus.SELESAI)

        done.outcome shouldBe AdvanceOutcome.ALREADY_COMPLETED
        verify(exactly = 0) { repository.updateStatus(any(), any(), any(), any()) }
        audit.second shouldBe emptyList()
    }

    @Test
    fun `should keep a cashier out of another branch's orders`() {
        val order = TxFixtures.order(branchId = NAROGONG.id)

        assertThrows<ForbiddenException> { advance(order, OrderStatus.DITERIMA) }.code shouldBe "BRANCH_SCOPE"
    }

    @Test
    fun `should move one step and queue the ready message when the customer is opted in now`() {
        val order = TxFixtures.order(status = OrderStatus.PROSES, customer = dewi)
        wantsReady(dewi)

        val result = advance(order, OrderStatus.PROSES)

        result.outcome shouldBe AdvanceOutcome.ADVANCED
        result.notificationQueued shouldBe true
        verify { repository.updateStatus(order.id, OrderStatus.SIAP, WaStatus.MENUNGGU, NOW) }
        verify {
            whatsApp.queueReadyForPickup(
                FAMILIA_URBAN.id,
                order.id,
                any(),
                "FMU-0915-001",
                "Familia Urban",
                FAMILIA_URBAN.hours,
            )
        }
        audit.second.single().action shouldBe "Ubah status FMU-0915-001 → Siap diambil · WA dijadwalkan"
    }

    @Test
    fun `should read consent when the order becomes ready, not the status stamped when it was paid`() {
        // Paid while opted in (waStatus MENUNGGU), opted out since (T7).
        val order = TxFixtures.order(status = OrderStatus.PROSES, customer = dewi)
        wantsReady(dewi.copy(optIn = false))

        val result = advance(order, OrderStatus.PROSES)

        result.notificationQueued shouldBe false
        verify { repository.updateStatus(order.id, OrderStatus.SIAP, WaStatus.BELUM_OPTIN, NOW) }
        audit.second.single().action shouldBe "Ubah status FMU-0915-001 → Siap diambil"
    }

    @Test
    fun `should queue nothing for a walk-in and nothing before the order is ready`() {
        wantsReady(null)
        advance(TxFixtures.order(status = OrderStatus.PROSES), OrderStatus.PROSES).notificationQueued shouldBe false
        advance(TxFixtures.order(status = OrderStatus.DITERIMA, customer = dewi), OrderStatus.DITERIMA)

        verify(exactly = 0) { whatsApp.queueReadyForPickup(any(), any(), any(), any(), any(), any()) }
        audit.second.map { it.action } shouldContainExactly
            listOf("Ubah status FMU-0915-001 → Siap diambil", "Ubah status FMU-0915-001 → Diproses")
    }
}
