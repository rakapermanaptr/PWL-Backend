package id.primawash.api.staff

import id.primawash.api.auth.PinHasher
import id.primawash.api.auth.SessionService
import id.primawash.api.branch.BranchService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.ConflictException
import id.primawash.api.common.SecureTokens
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FAMILIA_URBAN
import id.primawash.api.support.OWNER_ACTOR
import id.primawash.api.support.recordingAudit
import id.primawash.api.support.staffRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class StaffServiceTest {
    private val repository = mockk<StaffRepository>(relaxUnitFun = true)
    private val branches = mockk<BranchService>()
    private val sessions = mockk<SessionService>(relaxed = true)
    private val hasher = PinHasher("pepper-untuk-test")
    private val tokens = mockk<SecureTokens>()
    private val audit = recordingAudit()
    private val service =
        StaffService(DirectTransactionRunner, repository, branches, sessions, audit.first, hasher, tokens)

    init {
        every { branches.find(FAMILIA_URBAN.id) } returns FAMILIA_URBAN
        every { repository.existsActiveWithPinLookup(any(), any()) } returns false
    }

    @Test
    fun `should derive the short name the client shows`() {
        StaffService.shortNameOf("Siti Nurhaliza") shouldBe "Siti N."
        StaffService.shortNameOf("  Bagas   Ardhana Putra ") shouldBe "Bagas A."
        StaffService.shortNameOf("Raka") shouldBe "Raka"
    }

    @Test
    fun `should check name, pin format, pin use and branch in that order`() =
        runBlocking<Unit> {
            every { repository.existsActiveWithPinLookup(hasher.lookup("1234"), null) } returns true

            code { service.create(" ", "12", Role.KASIR, null, OWNER_ACTOR) } shouldBe "NAME_REQUIRED"
            code { service.create("Andi", "12ab", Role.KASIR, null, OWNER_ACTOR) } shouldBe "PIN_FORMAT"
            code { service.create("Andi", "1234567", Role.KASIR, null, OWNER_ACTOR) } shouldBe "PIN_FORMAT"
            code { service.create("Andi", "1234", Role.KASIR, null, OWNER_ACTOR) } shouldBe "PIN_TAKEN"
            code { service.create("Andi", "5555", Role.KASIR, null, OWNER_ACTOR) } shouldBe "CASHIER_NEEDS_BRANCH"
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `should store an owner without a branch and never the raw pin`() =
        runBlocking<Unit> {
            val branch = slot<UUID?>()
            val lookup = slot<String>()
            val hash = slot<String>()
            every {
                repository.insert(any(), any(), any(), any(), captureNullable(branch), capture(lookup), capture(hash))
            } answers {}
            every { repository.findById(any(), any()) } answers { staffRecord("Dewi Owner", Role.OWNER) }

            service.create("Dewi Owner", "246810", Role.OWNER, FAMILIA_URBAN.id, OWNER_ACTOR)

            branch.captured shouldBe null
            lookup.captured shouldBe hasher.lookup("246810")
            hasher.verify("246810", hash.captured) shouldBe true
            audit.second.single().action shouldBe "Tambah akun staff Dewi Owner (owner, semua cabang) · PIN dibuat"
        }

    @Test
    fun `should reset to a pin no account uses and revoke the account's sessions`() =
        runBlocking<Unit> {
            val bagas = staffRecord("Bagas Ardhana")
            every { repository.findById(bagas.id, any()) } returns bagas
            every { repository.allPinLookups() } returns setOf(hasher.lookup("1000"), hasher.lookup("1001"))
            every { tokens.nextInt(1000, 10_000) } returnsMany listOf(1000, 1001, 4821)

            val reset = service.resetPin(bagas.id, OWNER_ACTOR)

            reset.pin shouldBe "4821"
            verify { repository.updatePin(bagas.id, hasher.lookup("4821"), any()) }
            verify { sessions.revokeAllForStaff(bagas.id) }
            audit.second.single().action shouldBe "Reset PIN Bagas Ardhana — PIN lama dicabut"
        }

    @Test
    fun `should not let an owner deactivate their own account`() =
        runBlocking<Unit> {
            val raka = staffRecord("Raka Prasetyo", Role.OWNER)
            every { repository.findById(raka.id, any()) } returns raka
            code { service.setActive(raka.id, false, raka.id, OWNER_ACTOR) } shouldBe "STAFF_SELF_DEACTIVATE"
        }

    @Test
    fun `should keep at least one active owner`() =
        runBlocking<Unit> {
            val raka = staffRecord("Raka Prasetyo", Role.OWNER)
            every { repository.findById(raka.id, any()) } returns raka
            every { repository.lockActiveOwners() } returns listOf(raka)

            code { service.setActive(raka.id, false, UUID.randomUUID(), OWNER_ACTOR) } shouldBe "LAST_OWNER"
        }

    @Test
    fun `should refuse to reactivate an account whose pin another active account now uses`() =
        runBlocking<Unit> {
            val yuni = staffRecord("Yuni Astari", active = false)
            every { repository.findById(yuni.id, any()) } returns yuni
            every { repository.existsActiveWithPinLookup(yuni.pinLookup, yuni.id) } returns true

            val error =
                assertThrows<ConflictException> { service.setActive(yuni.id, true, UUID.randomUUID(), OWNER_ACTOR) }
            error.code shouldBe "PIN_CONFLICT"
            error.message shouldBe "PIN Yuni Astari sudah dipakai staff lain — reset PIN setelah akun diaktifkan."
        }

    @Test
    fun `should revoke sessions when an account is deactivated and skip a no-op`() =
        runBlocking<Unit> {
            val bagas = staffRecord("Bagas Ardhana")
            every { repository.findById(bagas.id, any()) } returns bagas

            service.setActive(bagas.id, true, UUID.randomUUID(), OWNER_ACTOR).changed shouldBe false
            audit.second.shouldBeEmpty()

            val change = service.setActive(bagas.id, false, UUID.randomUUID(), OWNER_ACTOR)
            change.changed shouldBe true
            verify { sessions.revokeAllForStaff(bagas.id) }
            audit.second.single().type shouldBe AuditActionType.STAFF_TOGGLED
            audit.second.single().branchId shouldNotBe null
        }

    private suspend fun code(block: suspend () -> Unit): String =
        try {
            block()
            error("Tidak ada aturan yang dilanggar")
        } catch (e: BusinessRuleException) {
            e.code
        }
}
