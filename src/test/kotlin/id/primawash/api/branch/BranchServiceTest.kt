package id.primawash.api.branch

import id.primawash.api.common.AuditActionType
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.NotFoundException
import id.primawash.api.support.BINTARO
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.OWNER_ACTOR
import id.primawash.api.support.TEBET
import id.primawash.api.support.recordingAudit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class BranchServiceTest {
    private val repository = mockk<BranchRepository>(relaxUnitFun = true)
    private val audit = recordingAudit()
    private val service = BranchService(DirectTransactionRunner, repository, audit.first)

    init {
        every { repository.findById(TEBET.id, any()) } returns TEBET
        every { repository.findById(BINTARO.id, any()) } returns BINTARO
    }

    @Test
    fun `should refuse an empty target and quote the target it keeps`() =
        runBlocking<Unit> {
            val error =
                assertThrows<BusinessRuleException> { service.update(TEBET.id, 0, null, BINTARO.id, OWNER_ACTOR) }
            error.code shouldBe "TARGET_REQUIRED"
            error.message shouldBe "Target harian tidak boleh kosong — dikembalikan ke Rp5.200.000."
            verify(exactly = 0) { repository.updateDailyTarget(any(), any()) }
        }

    @Test
    fun `should refuse to deactivate the branch the caller's device is on`() =
        runBlocking<Unit> {
            val error =
                assertThrows<BusinessRuleException> { service.update(TEBET.id, null, false, TEBET.id, OWNER_ACTOR) }
            error.code shouldBe "BRANCH_IN_USE"
        }

    @Test
    fun `should check every rule before writing anything`() =
        runBlocking<Unit> {
            assertThrows<BusinessRuleException> { service.update(TEBET.id, 6_000_000, false, TEBET.id, OWNER_ACTOR) }
            verify(exactly = 0) { repository.updateDailyTarget(any(), any()) }
            audit.second.shouldBeEmpty()
        }

    @Test
    fun `should audit a target change and a deactivation separately`() =
        runBlocking<Unit> {
            val result = service.update(BINTARO.id, 3_500_000, false, TEBET.id, OWNER_ACTOR)

            result.changed shouldBe true
            result.branch.dailyTarget shouldBe 3_500_000
            result.branch.active shouldBe false
            audit.second.map { it.type } shouldBe
                listOf(AuditActionType.BRANCH_TARGET_CHANGED, AuditActionType.BRANCH_TOGGLED)
            audit.second.map { it.action } shouldBe
                listOf("Ubah target harian Cabang Bintaro: Rp3.400.000 → Rp3.500.000", "Nonaktifkan Cabang Bintaro")
            audit.second.all { it.branchId == BINTARO.id } shouldBe true
        }

    @Test
    fun `should write nothing when the values do not change`() =
        runBlocking<Unit> {
            val result = service.update(TEBET.id, 5_200_000, true, TEBET.id, OWNER_ACTOR)
            result.changed shouldBe false
            audit.second.shouldBeEmpty()
        }

    @Test
    fun `should report an unknown branch`() =
        runBlocking<Unit> {
            val unknown = UUID.randomUUID()
            every { repository.findById(unknown, any()) } returns null
            assertThrows<NotFoundException> { service.update(unknown, 1, null, TEBET.id, OWNER_ACTOR) }.message shouldBe
                "Cabang tidak ditemukan."
        }
}
