package id.primawash.api.device

import id.primawash.api.auth.PinHasher
import id.primawash.api.auth.SessionService
import id.primawash.api.branch.BranchService
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.SecureTokens
import id.primawash.api.staff.StaffService
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.OWNER_ACTOR
import id.primawash.api.support.TEBET
import id.primawash.api.support.deviceRecord
import id.primawash.api.support.recordingAudit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID

class DeviceServiceTest {
    private val repository = mockk<DeviceRepository>(relaxUnitFun = true)
    private val branches = mockk<BranchService>()
    private val staff = mockk<StaffService>()
    private val sessions = mockk<SessionService>()
    private val hasher = PinHasher("pepper-untuk-test")
    private val audit = recordingAudit()
    private val service =
        DeviceService(
            DirectTransactionRunner,
            repository,
            branches,
            staff,
            sessions,
            audit.first,
            hasher,
            SecureTokens(),
            FIXED_CLOCK,
        )

    @Test
    fun `should issue an unambiguous code valid for a day and store only its keyed hash`() =
        runBlocking<Unit> {
            every { branches.find(TEBET.id) } returns TEBET
            val hash = slot<String>()
            every {
                repository.insertActivationCode(capture(hash), any(), TEBET.id, NOW, NOW.plus(Duration.ofHours(24)))
            } answers {}

            val issued = service.createActivationCode(TEBET.id, OWNER_ACTOR)

            issued.code shouldMatch Regex("^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$")
            hash.captured shouldBe hasher.keyedLookup("device-activation", issued.code.replace("-", ""))
            audit.second.single().action shouldBe "Buat kode aktivasi perangkat untuk Cabang Tebet · berlaku 24 jam"
        }

    @Test
    fun `should refuse a malformed, used or expired code`() =
        runBlocking<Unit> {
            assertThrows<BusinessRuleException> { service.activate("ABC", null, null) }.code shouldBe
                "ACTIVATION_CODE_INVALID"

            val used =
                ActivationCodeRecord(UUID.randomUUID(), UUID.randomUUID(), null, NOW.plusSeconds(60), usedAt = NOW)
            every {
                repository.findActivationCodeForUpdate(
                    hasher.keyedLookup("device-activation", "ABCDEFGH"),
                )
            } returns
                used
            assertThrows<BusinessRuleException> { service.activate("abcd-efgh", null, null) }.code shouldBe
                "ACTIVATION_CODE_INVALID"

            val expired = used.copy(usedAt = null, expiresAt = NOW)
            every { repository.findActivationCodeForUpdate(any()) } returns expired
            assertThrows<BusinessRuleException> { service.activate("ABCDEFGH", null, null) }.code shouldBe
                "ACTIVATION_CODE_INVALID"

            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `should revoke a device once and its sessions with it`() =
        runBlocking<Unit> {
            val tablet = deviceRecord()
            every { repository.findById(tablet.id, true) } returns tablet
            every { sessions.revokeAllForDevice(tablet.id) } returns 2

            service.revoke(tablet.id, OWNER_ACTOR).changed shouldBe true
            verify { repository.revoke(tablet.id, NOW) }
            audit.second.single().action shouldBe "Cabut perangkat Tablet Tebet 1"

            every { repository.findById(tablet.id, true) } returns tablet.copy(revokedAt = NOW)
            audit.second.clear()
            service.revoke(tablet.id, OWNER_ACTOR).changed shouldBe false
            audit.second.shouldBeEmpty()
        }
}
