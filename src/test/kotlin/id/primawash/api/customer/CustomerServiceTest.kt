package id.primawash.api.customer

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.OWNER_ACTOR
import id.primawash.api.support.TEBET
import id.primawash.api.support.recordingAudit
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class CustomerServiceTest {
    private val repository = mockk<CustomerRepository>(relaxUnitFun = true)
    private val audit = recordingAudit()
    private val service = CustomerService(DirectTransactionRunner, repository, audit.first, FIXED_CLOCK)

    private val dewi =
        CustomerRecord(UUID.randomUUID(), "Dewi Anggraini", "0812-3390-4471", "081233904471", 2_340, true, 18, TEBET.id)

    @Test
    fun `should check name, then phone format, then phone registration`() =
        runBlocking<Unit> {
            every { repository.findByPhoneDigits("081233904471") } returns dewi

            assertThrows<BusinessRuleException> {
                service.register(
                    null,
                    " ",
                    "081233904471",
                    true,
                    TEBET.id,
                    OWNER_ACTOR,
                )
            }.code shouldBe "NAME_REQUIRED"
            val invalid =
                assertThrows<BusinessRuleException> {
                    service.register(
                        null,
                        "Bayu",
                        "0812-339",
                        false,
                        TEBET.id,
                        OWNER_ACTOR,
                    )
                }
            invalid.code shouldBe "PHONE_INVALID"
            invalid.message shouldBe "Nomor WhatsApp belum valid (minimal 10 digit, diawali 08)."
            assertThrows<BusinessRuleException> {
                service.register(
                    null,
                    "Bayu",
                    "0812 abc 4471",
                    false,
                    TEBET.id,
                    OWNER_ACTOR,
                )
            }.code shouldBe "PHONE_INVALID"

            val taken =
                assertThrows<ConflictException> {
                    service.register(
                        null,
                        "Dewi",
                        "+62 812-3390-4471",
                        false,
                        TEBET.id,
                        OWNER_ACTOR,
                    )
                }
            taken.code shouldBe "PHONE_ALREADY_REGISTERED"
            taken.details!!["customer"]!!
                .jsonObject["id"]!!
                .jsonPrimitive.content shouldBe dewi.id.toString()
        }

    @Test
    fun `should store the display phone, the branch of the token and consent time`() =
        runBlocking<Unit> {
            every { repository.findByPhoneDigits(any()) } returns null
            every { repository.findById(any(), any()) } returns dewi

            service.register(null, "Dewi Anggraini", "62 812 3390 4471", true, TEBET.id, OWNER_ACTOR)

            verify {
                repository.insert(
                    any(),
                    "Dewi Anggraini",
                    "0812-3390-4471",
                    "081233904471",
                    true,
                    TEBET.id,
                    any(),
                    NOW,
                )
            }
            audit.second.single().action shouldBe "Daftarkan customer Dewi Anggraini (0812-3390-4471) · opt-in WA: ya"
        }

    @Test
    fun `should cancel queued messages when a customer opts out`() =
        runBlocking<Unit> {
            every { repository.findById(dewi.id, any()) } returns dewi
            every { repository.cancelQueuedWhatsApp(dewi.id, any()) } returns 1

            val result = service.update(dewi.id, null, false, TEBET.id, OWNER_ACTOR)

            result.changed shouldBe true
            verify { repository.updateOptIn(dewi.id, false, NOW) }
            verify { repository.cancelQueuedWhatsApp(dewi.id, any()) }
            audit.second.single().type shouldBe AuditActionType.CUSTOMER_OPTED_OUT
        }

    @Test
    fun `should refuse a blank name and ignore unchanged values`() =
        runBlocking<Unit> {
            every { repository.findById(dewi.id, any()) } returns dewi
            assertThrows<BusinessRuleException> {
                service.update(
                    dewi.id,
                    "  ",
                    null,
                    TEBET.id,
                    OWNER_ACTOR,
                )
            }.code shouldBe
                "NAME_REQUIRED"

            service.update(dewi.id, "Dewi Anggraini", true, TEBET.id, OWNER_ACTOR).changed shouldBe false
            verify(exactly = 0) { repository.cancelQueuedWhatsApp(any(), any()) }
        }
}
