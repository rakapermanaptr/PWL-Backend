package id.primawash.api.auth

import id.primawash.api.branch.BranchService
import id.primawash.api.common.AuditActionType
import id.primawash.api.common.BusinessRuleException
import id.primawash.api.common.DomainException
import id.primawash.api.common.ForbiddenException
import id.primawash.api.common.PinLockedException
import id.primawash.api.common.SecureTokens
import id.primawash.api.device.DeviceService
import id.primawash.api.staff.Role
import id.primawash.api.staff.StaffService
import id.primawash.api.support.BINTARO
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.TEBET
import id.primawash.api.support.deviceRecord
import id.primawash.api.support.principal
import id.primawash.api.support.recordingAudit
import id.primawash.api.support.staffRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID

class AuthServiceTest {
    private val repository = mockk<AuthRepository>(relaxUnitFun = true)
    private val staff = mockk<StaffService>(relaxUnitFun = true)
    private val branches = mockk<BranchService>()
    private val devices = mockk<DeviceService>(relaxUnitFun = true)
    private val audit = recordingAudit()
    private val service =
        AuthService(
            DirectTransactionRunner,
            repository,
            staff,
            branches,
            devices,
            audit.first,
            AccessTokens("secret-untuk-test", FIXED_CLOCK),
            SecureTokens(),
            FIXED_CLOCK,
        )

    private val tablet = deviceRecord()
    private val siti = staffRecord("Siti Nurhaliza")

    init {
        every { branches.find(TEBET.id) } returns TEBET
        every { branches.find(BINTARO.id) } returns BINTARO
        every { devices.registerForPinAttempt(tablet.id, any()) } returns tablet
        every { staff.findByPin(any()) } returns emptyList()
        every { staff.verifyPin(any(), any()) } returns true
        every { repository.countFailedPinAttempts(any(), any()) } returns 1
        every { repository.insertSession(any(), any(), any(), any(), any(), any()) } returns UUID.randomUUID()
        every { repository.revokeSessionsOfDevice(any(), any(), any()) } returns 0
    }

    @Test
    fun `should check branch, branch state and pin format before touching any pin`() =
        runBlocking<Unit> {
            every { branches.find(BINTARO.id) } returns BINTARO.copy(active = false)

            rejection { service.pinLogin(tablet.id, "1.4.0", null, "1234") }.let {
                it.code shouldBe "BRANCH_REQUIRED"
                it.clearPin() shouldBe false
            }
            rejection { service.pinLogin(tablet.id, "1.4.0", BINTARO.id, "1234") }.message shouldBe
                "Cabang Bintaro sedang nonaktif — pilih cabang lain atau hubungi owner."
            rejection { service.pinLogin(tablet.id, "1.4.0", TEBET.id, "12") }.code shouldBe "PIN_FORMAT"

            verify(exactly = 0) { staff.findByPin(any()) }
            audit.second.shouldBeEmpty()
        }

    @Test
    fun `should refuse a locked tablet without evaluating or auditing the pin`() =
        runBlocking<Unit> {
            every { devices.registerForPinAttempt(tablet.id, any()) } returns
                tablet.copy(pinLockedUntil = NOW.plusSeconds(90))

            val locked = assertThrows<PinLockedException> { service.pinLogin(tablet.id, "1.4.0", TEBET.id, "1234") }

            locked.message shouldBe "Terlalu banyak PIN salah. Coba lagi dalam 2 menit."
            locked.retryAfterSeconds shouldBe 90
            verify(exactly = 0) { staff.findByPin(any()) }
            audit.second.shouldBeEmpty()
        }

    @Test
    fun `should audit an unknown pin and lock the tablet on the fifth failure in the window`() =
        runBlocking<Unit> {
            every { repository.countFailedPinAttempts(tablet.id, NOW.minus(Duration.ofMinutes(5))) } returns 5

            val error = rejection { service.pinLogin(tablet.id, "1.4.0", TEBET.id, "0000") }

            error.code shouldBe "PIN_UNKNOWN"
            error.clearPin() shouldBe true
            verify { devices.setPinLock(tablet.id, NOW.plus(Duration.ofMinutes(5))) }
            audit.second.map { it.type } shouldBe listOf(AuditActionType.LOGIN_FAILED, AuditActionType.PIN_LOCKED)
            audit.second
                .first()
                .actor.actorName shouldBe "Perangkat Tablet Cabang Tebet"
        }

    @Test
    fun `should tell an inactive account and a cashier from another branch apart`() =
        runBlocking<Unit> {
            val yuni = staffRecord("Yuni Astari", active = false)
            every { staff.findByPin("4321") } returns listOf(yuni)
            rejection { service.pinLogin(tablet.id, "1.4.0", TEBET.id, "4321") }.message shouldBe
                "Akun Yuni Astari nonaktif — PIN lama sudah diblokir."

            val nia = staffRecord("Nia Ramadhani", branchId = BINTARO.id)
            every { staff.findByPin("2468") } returns listOf(nia)
            rejection { service.pinLogin(tablet.id, "1.4.0", TEBET.id, "2468") }.message shouldBe
                "Nia Ramadhani terdaftar di Cabang Bintaro — tidak bisa login di perangkat Cabang Tebet."

            audit.second.count { it.type == AuditActionType.LOGIN_FAILED } shouldBe 2
        }

    @Test
    fun `should prefer the active account when an inactive one shares the pin`() =
        runBlocking<Unit> {
            every { staff.findByPin("1234") } returns listOf(staffRecord("Yuni Astari", active = false), siti)

            val session = service.pinLogin(tablet.id, "1.4.0", TEBET.id, "1234")

            session.context.staff.id shouldBe siti.id
            session.refreshTokenExpiresIn shouldBe Duration.ofHours(18).seconds
            verify { staff.recordSuccessfulLogin(siti.id, NOW) }
            verify { repository.revokeSessionsOfDevice(tablet.id, NOW, null) }
            verify { devices.recordLogin(tablet, TEBET.id, "Tebet", "1.4.0") }
            audit.second.single().action shouldBe "Login PIN sebagai kasir Tebet di perangkat Cabang Tebet"
        }

    @Test
    fun `should refuse a blocked tablet before looking at the pin`() =
        runBlocking<Unit> {
            every { devices.registerForPinAttempt(tablet.id, any()) } returns null

            rejection { service.pinLogin(tablet.id, null, TEBET.id, "1234") }.code shouldBe "DEVICE_UNAUTHORIZED"
            verify(exactly = 0) { staff.findByPin(any()) }
        }

    @Test
    fun `should audit a tablet's very first login`() =
        runBlocking<Unit> {
            val fresh = deviceRecord(name = "Tablet", lastBranchId = null)
            every { devices.registerForPinAttempt(fresh.id, any()) } returns fresh
            every { staff.findByPin("1234") } returns listOf(siti)

            service.pinLogin(fresh.id, null, TEBET.id, "1234")

            audit.second.map { it.type } shouldBe listOf(AuditActionType.DEVICE_ACTIVATED, AuditActionType.LOGIN)
            audit.second.first().action shouldBe "Tablet baru dipakai login pertama kali di Cabang Tebet"
        }

    @Test
    fun `should let an owner log in at any branch`() =
        runBlocking<Unit> {
            val raka = staffRecord("Raka Prasetyo", Role.OWNER)
            every { staff.findByPin("9090") } returns listOf(raka)

            service.pinLogin(tablet.id, "1.4.0", BINTARO.id, "9090")

            audit.second.single().action shouldBe "Login PIN sebagai owner/admin di perangkat Cabang Bintaro"
        }

    @Test
    fun `should verify a chosen staff member in the client's order`() =
        runBlocking<Unit> {
            val caller = principal(siti, deviceId = tablet.id)
            val yuni = staffRecord("Yuni Astari", active = false)
            val nia = staffRecord("Nia Ramadhani", branchId = BINTARO.id)
            every { staff.find(yuni.id, true) } returns yuni
            every { staff.find(nia.id, true) } returns nia
            every { staff.find(siti.id, true) } returns siti

            rejection { service.verifyPin(caller, null, "1234") }.message shouldBe "Pilih staff dulu."
            rejection { service.verifyPin(caller, yuni.id, "4321") }.message shouldBe
                "Akun Yuni Astari nonaktif — PIN lama tidak bisa dipakai."
            rejection { service.verifyPin(caller, nia.id, "2468") }.code shouldBe "STAFF_WRONG_BRANCH"
            rejection { service.verifyPin(caller, siti.id, "1") }.message shouldBe "PIN minimal 4 digit."
        }

    @Test
    fun `should lock an account for fifteen minutes on the fifth consecutive wrong pin`() =
        runBlocking<Unit> {
            val bagas = staffRecord("Bagas Ardhana", failedCount = 4)
            every { staff.find(bagas.id, true) } returns bagas
            every { staff.verifyPin("1111", bagas) } returns false

            rejection { service.verifyPin(principal(siti, deviceId = tablet.id), bagas.id, "1111") }.message shouldBe
                "PIN salah untuk Bagas Ardhana. Coba lagi."

            verify { staff.recordPinFailures(bagas.id, 0, NOW.plus(Duration.ofMinutes(15))) }
            audit.second.last().action shouldBe "Akun Bagas Ardhana dikunci 15 menit — 5 PIN salah berturut-turut"
        }

    @Test
    fun `should refuse a locked account even with the right pin`() =
        runBlocking<Unit> {
            val bagas = staffRecord("Bagas Ardhana", lockedUntil = NOW.plus(Duration.ofMinutes(15)))
            every { staff.find(bagas.id, true) } returns bagas

            assertThrows<PinLockedException> {
                service.verifyPin(
                    principal(siti, deviceId = tablet.id),
                    bagas.id,
                    "5678",
                )
            }.message shouldBe "Terlalu banyak PIN salah. Coba lagi dalam 15 menit."
            verify(exactly = 0) { staff.verifyPin(any(), any()) }
        }

    @Test
    fun `should keep a cashier's tablet on their branch and skip a move to the same branch`() =
        runBlocking<Unit> {
            assertThrows<ForbiddenException> { service.switchBranch(principal(siti), BINTARO.id) }.let {
                it.code shouldBe "OWNER_ONLY"
                it.message shouldBe
                    "Perangkat kasir terikat ke Cabang Tebet. " +
                    "Hanya owner/admin yang bisa memindahkan perangkat ke cabang lain."
            }

            val raka = staffRecord("Raka Prasetyo", Role.OWNER)
            every { staff.find(raka.id) } returns raka
            val same = service.switchBranch(principal(raka), TEBET.id)
            same.session shouldBe null
            audit.second.shouldBeEmpty()

            every { branches.find(BINTARO.id) } returns BINTARO.copy(active = false)
            assertThrows<BusinessRuleException> { service.switchBranch(principal(raka), BINTARO.id) }.message shouldBe
                "Cabang Bintaro nonaktif — aktifkan dulu di halaman Cabang sebelum memindahkan perangkat."
        }

    private suspend fun rejection(block: suspend () -> Unit): DomainException =
        try {
            block()
            error("Tidak ditolak")
        } catch (e: DomainException) {
            e
        }

    private fun DomainException.clearPin(): Boolean? = details?.get("clearPin")?.jsonPrimitive?.boolean
}
