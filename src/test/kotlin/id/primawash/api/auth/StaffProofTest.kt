package id.primawash.api.auth

import id.primawash.api.branch.BranchService
import id.primawash.api.common.SecureTokens
import id.primawash.api.device.DeviceService
import id.primawash.api.staff.StaffService
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FAMILIA_URBAN
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NAROGONG
import id.primawash.api.support.NOW
import id.primawash.api.support.principal
import id.primawash.api.support.recordingAudit
import id.primawash.api.support.staffRecord
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `staffProof` spent by `POST /shifts` (PRD §8.7): one use, this tablet, this branch, and a staff member
 * who may still work here.
 */
class StaffProofTest {
    private val repository = mockk<AuthRepository>(relaxUnitFun = true)
    private val staff = mockk<StaffService>()
    private val service =
        AuthService(
            DirectTransactionRunner,
            repository,
            staff,
            mockk<BranchService>(),
            mockk<DeviceService>(),
            recordingAudit().first,
            AccessTokens("secret-untuk-test", FIXED_CLOCK),
            SecureTokens(),
            FIXED_CLOCK,
        )

    private val bagas = staffRecord("Bagas Ardhana")
    private val siti = principal(staffRecord("Siti Nurhaliza"))
    private val proof = "sp_bukti-verify-pin"
    private val valid =
        StaffProofRecord(UUID.randomUUID(), bagas.id, siti.deviceId, FAMILIA_URBAN.id, NOW.plusSeconds(60), null)

    init {
        every { staff.find(bagas.id) } returns bagas
    }

    private fun spend(record: StaffProofRecord?): Boolean {
        every { repository.findStaffProofForUpdate(SecureTokens.sha256Hex(proof)) } returns record
        return service.consumeStaffProof(siti, proof) != null
    }

    @Test
    fun `should spend a valid proof once and name its staff member`() {
        spend(valid) shouldBe true
        verify { repository.markStaffProofUsed(valid.id, NOW) }
    }

    @Test
    fun `should refuse a used, expired, foreign or unknown proof without spending it`() {
        spend(null) shouldBe false
        spend(valid.copy(usedAt = NOW.minusSeconds(5))) shouldBe false
        spend(valid.copy(expiresAt = NOW)) shouldBe false
        spend(valid.copy(deviceId = UUID.randomUUID())) shouldBe false
        spend(valid.copy(branchId = NAROGONG.id)) shouldBe false
        service.consumeStaffProof(siti, null) shouldBe null
        service.consumeStaffProof(siti, "rt_bukan-proof") shouldBe null

        verify(exactly = 0) { repository.markStaffProofUsed(any(), any()) }
    }

    @Test
    fun `should refuse a proof whose staff member was deactivated or moved since`() {
        every { staff.find(bagas.id) } returns bagas.copy(active = false)
        spend(valid) shouldBe false

        every { staff.find(bagas.id) } returns bagas.copy(branchId = NAROGONG.id)
        spend(valid) shouldBe false

        verify(exactly = 0) { repository.markStaffProofUsed(any(), any()) }
    }
}
