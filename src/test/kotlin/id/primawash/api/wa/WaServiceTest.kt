package id.primawash.api.wa

import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.TEBET
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WaServiceTest {
    private val repository =
        mockk<WaRepository> {
            every { insertQueued(any(), any(), any(), any(), any(), any(), any()) } returns UUID.randomUUID()
        }
    private val service = WaService(repository, FIXED_CLOCK)
    private val orderId = UUID.randomUUID()
    private val consentAt = Instant.parse("2026-09-01T00:00:00Z")
    private val dewi =
        WaRecipient(UUID.randomUUID(), "Dewi Anggraini", "0812-3390-4471", optIn = true, optInAt = consentAt)
    private val receipt =
        ReceiptParams("TBT-0915-001", "Tebet", "Cuci Setrika 4,5 kg", "Rp31.000", "Tunai", "300", "1.140")

    private fun queuedTemplates(): List<WaTemplate> {
        val templates = mutableListOf<WaTemplate>()
        verify { repository.insertQueued(any(), any(), any(), capture(templates), any(), any(), any()) }
        return templates
    }

    @Test
    fun `should confirm consent on the first order after opting in, then send the receipt`() {
        every { repository.existsSince(dewi.customerId, WaTemplate.OPT_IN_CONFIRM, consentAt) } returns false

        service.queueForPaidOrder(TEBET.id, orderId, dewi, receipt) shouldBe true

        queuedTemplates() shouldContainExactly listOf(WaTemplate.OPT_IN_CONFIRM, WaTemplate.STRUK_DIGITAL)
        verify { repository.insertQueued(TEBET.id, orderId, dewi.customerId, any(), "6281233904471", any(), NOW) }
    }

    @Test
    fun `should send only the receipt once consent was confirmed`() {
        every { repository.existsSince(dewi.customerId, WaTemplate.OPT_IN_CONFIRM, consentAt) } returns true

        service.queueForPaidOrder(TEBET.id, orderId, dewi, receipt)

        queuedTemplates() shouldContainExactly listOf(WaTemplate.STRUK_DIGITAL)
    }

    @Test
    fun `should queue nothing at all for a customer who is not opted in now`() {
        val notOptedIn = dewi.copy(optIn = false)

        service.queueForPaidOrder(TEBET.id, orderId, notOptedIn, receipt) shouldBe false
        service.queueReadyForPickup(TEBET.id, orderId, notOptedIn, "TBT-0915-001", "Tebet", "07.00 – 21.00") shouldBe
            false

        verify(exactly = 0) { repository.insertQueued(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `should queue the ready message for an opted in customer`() {
        service.queueReadyForPickup(TEBET.id, orderId, dewi, "TBT-0915-001", "Tebet", "07.00 – 21.00") shouldBe true

        queuedTemplates() shouldContainExactly listOf(WaTemplate.STATUS_SIAP_DIAMBIL)
    }
}
