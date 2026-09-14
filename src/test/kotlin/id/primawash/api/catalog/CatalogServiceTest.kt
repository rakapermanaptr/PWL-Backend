package id.primawash.api.catalog

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.NotFoundException
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.OWNER_ACTOR
import id.primawash.api.support.recordingAudit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class CatalogServiceTest {
    private val repository = mockk<CatalogRepository>(relaxUnitFun = true)
    private val audit = recordingAudit()
    private val service = CatalogService(DirectTransactionRunner, repository, audit.first, FIXED_CLOCK)

    private val cuciSetrika =
        ServiceRecord(
            UUID.randomUUID(),
            ServiceCategory.KILOAN_REGULER,
            "Cuci Setrika",
            10_000,
            "kg",
            BigDecimal("0.5"),
            true,
        )
    private val cuciKering =
        ServiceRecord(
            UUID.randomUUID(),
            ServiceCategory.KILOAN_REGULER,
            "Cuci Kering",
            7_000,
            "kg",
            BigDecimal("0.5"),
            true,
        )

    init {
        every { repository.findService(cuciSetrika.id, any()) } returns cuciSetrika
        every { repository.findService(cuciKering.id, any()) } returns cuciKering
        every { repository.findServices(true) } returns listOf(cuciSetrika.copy(price = 11_000), cuciKering)
    }

    @Test
    fun `should check a new service's name, price and uniqueness in order`() =
        runBlocking<Unit> {
            every { repository.serviceNameExists("Bed Cover") } returns true

            code { service.addService(ServiceCategory.SATUAN, "  ", 0, "pcs", OWNER_ACTOR) } shouldBe
                ("NAME_REQUIRED" to "Nama layanan wajib diisi.")
            code { service.addService(ServiceCategory.SATUAN, "Bed Cover", 0, "pcs", OWNER_ACTOR) } shouldBe
                ("PRICE_REQUIRED" to "Harga per unit belum diisi.")
            code { service.addService(ServiceCategory.SATUAN, "Bed Cover", 35_000, "pcs", OWNER_ACTOR) } shouldBe
                ("SERVICE_NAME_TAKEN" to "Layanan dengan nama ini sudah ada di price list.")
        }

    @Test
    fun `should give kg services a half step and others a whole step`() =
        runBlocking<Unit> {
            every { repository.serviceNameExists(any()) } returns false
            every { repository.findService(any(), any()) } returns cuciSetrika

            service.addService(ServiceCategory.KILOAN_EXPRESS, "Kilat", 14_000, "kg", OWNER_ACTOR)
            service.addService(ServiceCategory.SATUAN, "Karpet Besar", 40_000, "m²", OWNER_ACTOR)

            verify { repository.insertService(any(), any(), "Kilat", 14_000, "kg", BigDecimal("0.5")) }
            verify { repository.insertService(any(), any(), "Karpet Besar", 40_000, "m²", BigDecimal("1.0")) }
            audit.second.first().action shouldBe "Tambah layanan Kilat — Rp14.000/kg (Kiloan Express)"
        }

    @Test
    fun `should refuse an empty price before looking anything up`() =
        runBlocking<Unit> {
            code { service.savePrices(mapOf(cuciSetrika.id to 11_000, cuciKering.id to 0), OWNER_ACTOR) } shouldBe
                ("PRICE_REQUIRED" to "Harga layanan tidak boleh kosong.")
            verify(exactly = 0) { repository.findService(any(), any()) }
        }

    @Test
    fun `should audit each real price change and one summary`() =
        runBlocking<Unit> {
            val result = service.savePrices(mapOf(cuciSetrika.id to 11_000, cuciKering.id to 7_000), OWNER_ACTOR)

            result.changedCount shouldBe 1
            verify(exactly = 1) { repository.updateServicePrice(cuciSetrika.id, 10_000, 11_000, any(), NOW) }
            verify(exactly = 0) { repository.updateServicePrice(cuciKering.id, any(), any(), any(), any()) }
            audit.second.map { it.action } shouldBe
                listOf(
                    "Ubah harga Cuci Setrika: Rp10.000 → Rp11.000 (semua cabang)",
                    "Simpan price list — 2 layanan aktif di semua cabang",
                )
            audit.second.all { it.branchId == null && it.type == AuditActionType.SERVICE_PRICE_CHANGED } shouldBe true
        }

    @Test
    fun `should write nothing when no price changes and refuse an unknown service`() =
        runBlocking<Unit> {
            service.savePrices(mapOf(cuciKering.id to 7_000), OWNER_ACTOR).changedCount shouldBe 0
            audit.second.shouldBeEmpty()

            val unknown = UUID.randomUUID()
            every { repository.findService(unknown, any()) } returns null
            assertThrows<NotFoundException> { service.savePrices(mapOf(unknown to 5_000), OWNER_ACTOR) }
        }

    @Test
    fun `should validate the loyalty rate with the client's messages`() =
        runBlocking<Unit> {
            code { service.saveRate(999, 100, OWNER_ACTOR) } shouldBe
                ("RATE_INVALID" to "Nominal belanja minimal Rp1.000.")
            code { service.saveRate(10_000, 0, OWNER_ACTOR) } shouldBe ("RATE_INVALID" to "Poin didapat minimal 1.")
        }

    @Test
    fun `should insert a new rate effective now and never rewrite history`() =
        runBlocking<Unit> {
            val old = LoyaltyRateRecord(UUID.randomUUID(), 10_000, 100, Instant.EPOCH)
            val new = LoyaltyRateRecord(UUID.randomUUID(), 10_000, 150, NOW)
            every { repository.rateEffectiveAt(NOW) } returnsMany listOf(old, new)
            every { repository.insertRate(10_000, 150, NOW, any()) } returns new.id

            val change = service.saveRate(10_000, 150, OWNER_ACTOR)

            change.changed shouldBe true
            change.rate shouldBe new
            audit.second.single().action shouldBe
                "Simpan pengaturan loyalty — Rp10.000 = 150 poin, berlaku semua cabang"
        }

    @Test
    fun `should not insert a rate identical to the one in effect`() =
        runBlocking<Unit> {
            every { repository.rateEffectiveAt(NOW) } returns
                LoyaltyRateRecord(UUID.randomUUID(), 10_000, 100, Instant.EPOCH)
            service.saveRate(10_000, 100, OWNER_ACTOR).changed shouldBe false
            verify(exactly = 0) { repository.insertRate(any(), any(), any(), any()) }
        }

    @Test
    fun `should validate a reward and default its note`() =
        runBlocking<Unit> {
            code { service.addReward("", 1_000, 10_000, null, OWNER_ACTOR) } shouldBe
                ("NAME_REQUIRED" to "Nama reward wajib diisi.")
            code { service.addReward("Diskon", 0, 10_000, null, OWNER_ACTOR) } shouldBe
                ("REWARD_INVALID" to "Poin dan nilai diskon wajib diisi.")
            code { service.addReward("Diskon", 1_000, null, null, OWNER_ACTOR) } shouldBe
                ("REWARD_INVALID" to "Poin dan nilai diskon wajib diisi.")

            every { repository.findReward(any(), any()) } returns
                RewardRecord(UUID.randomUUID(), "Diskon Rp10.000", 1_000, 10_000, "Setara Rp10.000", null, 0, true)
            service.addReward("Diskon Rp10.000", 1_000, 10_000, 0, OWNER_ACTOR)
            verify { repository.insertReward(any(), "Diskon Rp10.000", 1_000, 10_000, "Setara Rp10.000", null) }
            audit.second.single().action shouldBe "Tambah reward Diskon Rp10.000 — 1.000 poin, nilai Rp10.000"
        }

    private suspend fun code(block: suspend () -> Unit): Pair<String, String> =
        try {
            block()
            error("Tidak ada aturan yang dilanggar")
        } catch (e: BusinessRuleException) {
            e.code to e.message
        }
}
