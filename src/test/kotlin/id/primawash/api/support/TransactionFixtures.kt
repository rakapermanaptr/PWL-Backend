package id.primawash.api.support

import id.primawash.api.catalog.LoyaltyRateRecord
import id.primawash.api.catalog.RewardRecord
import id.primawash.api.catalog.ServiceCategory
import id.primawash.api.catalog.ServiceRecord
import id.primawash.api.common.IdempotencyRequest
import id.primawash.api.common.IdempotencyStore
import id.primawash.api.common.Idempotent
import id.primawash.api.customer.CustomerRecord
import id.primawash.api.order.OrderItemRecord
import id.primawash.api.order.OrderRecord
import id.primawash.api.order.OrderSource
import id.primawash.api.order.OrderStatus
import id.primawash.api.order.PaymentMethod
import id.primawash.api.order.WaStatus
import id.primawash.api.shift.ShiftRecord
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Master data and transaction rows for the M2 service tests, shaped like the pilot seed (PRD Lampiran D). */
object TxFixtures {
    val CUCI_SETRIKA = service("Cuci Setrika", 10_000, "kg")
    val CUCI_KERING = service("Cuci Kering", 7_000, "kg")
    val BED_COVER = service("Bed Cover", 35_000, "pcs", ServiceCategory.SATUAN)
    val GORDEN = service("Gorden", 18_000, "kg", ServiceCategory.SATUAN, active = false)

    /** Rp10.000 = 100 poin. */
    val RATE = LoyaltyRateRecord(UUID.randomUUID(), 10_000, 100, Instant.parse("2026-01-01T00:00:00Z"))

    val GRATIS_CUCI_KERING = reward("Gratis Cuci Kering 2 kg", cost = 1_500, value = 14_000)
    val DISKON_25 = reward("Diskon Rp25.000", cost = 2_500, value = 25_000, minSubtotal = 75_000)

    fun service(
        name: String,
        price: Long,
        unit: String,
        category: ServiceCategory = ServiceCategory.KILOAN_REGULER,
        active: Boolean = true,
    ) = ServiceRecord(
        UUID.randomUUID(),
        category,
        name,
        price,
        unit,
        if (unit == "kg") BigDecimal("0.5") else BigDecimal("1.0"),
        active,
    )

    fun reward(
        name: String,
        cost: Long,
        value: Long,
        minSubtotal: Long? = null,
        active: Boolean = true,
    ) = RewardRecord(UUID.randomUUID(), name, cost, value, "", minSubtotal, 0, active)

    fun customer(
        name: String = "Dewi Anggraini",
        points: Long = 2_340,
        optIn: Boolean = true,
        optInAt: Instant? = NOW.minusSeconds(86_400),
    ) = CustomerRecord(
        UUID.randomUUID(),
        name,
        "0812-3390-4471",
        "081233904471",
        points,
        optIn,
        18,
        FAMILIA_URBAN.id,
        optInAt,
    )

    fun shift(
        branchId: UUID = FAMILIA_URBAN.id,
        closedAt: Instant? = null,
        openingCash: Long = 500_000,
        cashSales: Long = 0,
    ) = ShiftRecord(
        id = UUID.randomUUID(),
        branchId = branchId,
        openedByStaffId = UUID.randomUUID(),
        openedByName = "Siti Nurhaliza",
        openedAt = NOW.minusSeconds(3_600),
        closedAt = closedAt,
        closedByStaffId = null,
        openingCash = openingCash,
        cashSales = cashSales,
        transferSales = 0,
        txCount = 0,
        pointsIssued = 0,
        countedCash = openingCash,
        recapExpected = closedAt?.let { openingCash + cashSales },
        recapActual = closedAt?.let { openingCash + cashSales },
        version = 1,
    )

    fun order(
        status: OrderStatus = OrderStatus.DITERIMA,
        customer: CustomerRecord? = null,
        branchId: UUID = FAMILIA_URBAN.id,
        waStatus: WaStatus = if (customer?.optIn == true) WaStatus.MENUNGGU else WaStatus.BELUM_OPTIN,
    ) = OrderRecord(
        id = UUID.randomUUID(),
        number = "FMU-0915-001",
        branchId = branchId,
        businessDate = LocalDate.parse("2026-09-15"),
        clientTxId = UUID.randomUUID(),
        source = OrderSource.ONLINE,
        customerId = customer?.id,
        customerName = customer?.name ?: "Tanpa nama",
        customerPhone = customer?.phone ?: "—",
        note = "",
        subtotal = 20_000,
        discount = 0,
        total = 20_000,
        rewardId = null,
        rewardName = null,
        redeemedPoints = 0,
        earnedPoints = if (customer != null) 200 else 0,
        loyaltyRateId = RATE.id,
        payment = PaymentMethod.TUNAI,
        status = status,
        waStatus = waStatus,
        shiftId = UUID.randomUUID(),
        staffId = UUID.randomUUID(),
        deviceId = UUID.randomUUID(),
        capturedAt = NOW,
        createdAt = NOW,
        statusChangedAt = NOW,
        flags = emptyList(),
        version = 1,
        items = listOf(OrderItemRecord(CUCI_SETRIKA.id, "Cuci Setrika", BigDecimal("2.0"), "kg", 10_000, 20_000)),
    )

    val IDEMPOTENCY = IdempotencyRequest(UUID.randomUUID(), UUID.randomUUID(), "POST /test", "hash")

    /** An idempotency store with no stored keys: every action runs fresh, inside the direct "transaction". */
    fun freshIdempotency(): IdempotencyStore {
        val store = mockk<IdempotencyStore>()
        coEvery { store.execute<Any?>(any(), any(), any(), any()) } coAnswers {
            Idempotent.Fresh(arg<() -> Any?>(3).invoke())
        }
        return store
    }
}
