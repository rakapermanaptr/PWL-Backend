package id.primawash.api.device

import id.primawash.api.auth.SessionService
import id.primawash.api.support.DirectTransactionRunner
import id.primawash.api.support.FAMILIA_URBAN
import id.primawash.api.support.FIXED_CLOCK
import id.primawash.api.support.NOW
import id.primawash.api.support.OWNER_ACTOR
import id.primawash.api.support.deviceRecord
import id.primawash.api.support.recordingAudit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DeviceServiceTest {
    private val repository = mockk<DeviceRepository>(relaxUnitFun = true)
    private val sessions = mockk<SessionService>()
    private val audit = recordingAudit()
    private val service = DeviceService(DirectTransactionRunner, repository, sessions, audit.first, FIXED_CLOCK)

    @Test
    fun `should record a new installation and refuse a blocked one`() {
        val fresh = deviceRecord(name = "Tablet", lastBranchId = null)
        every { repository.findById(fresh.id, true) } returns fresh

        service.registerForPinAttempt(fresh.id, "1.4.0") shouldBe fresh
        verify { repository.insertIfAbsent(fresh.id, "1.4.0", NOW) }

        every { repository.findById(fresh.id, true) } returns fresh.copy(revokedAt = NOW)
        service.registerForPinAttempt(fresh.id, null) shouldBe null
    }

    @Test
    fun `should name a tablet after its branch on first login and keep a name already given`() {
        val fresh = deviceRecord(name = "Tablet", lastBranchId = null)
        service.recordLogin(fresh, FAMILIA_URBAN.id, "Familia Urban", "1.4.0")
        verify { repository.recordLogin(fresh.id, FAMILIA_URBAN.id, "Tablet Cabang Familia Urban", "1.4.0", NOW) }

        val named = deviceRecord(name = "Tablet Kasir Depan")
        service.recordLogin(named, FAMILIA_URBAN.id, "Familia Urban", null)
        verify { repository.recordLogin(named.id, FAMILIA_URBAN.id, "Tablet Kasir Depan", null, NOW) }
    }

    @Test
    fun `should block a device once and end its sessions with it`() =
        runBlocking<Unit> {
            val tablet = deviceRecord()
            every { repository.findById(tablet.id, true) } returns tablet
            every { sessions.revokeAllForDevice(tablet.id) } returns 2

            service.revoke(tablet.id, OWNER_ACTOR).changed shouldBe true
            verify { repository.revoke(tablet.id, NOW) }
            audit.second.single().action shouldBe "Blokir perangkat Tablet Cabang Familia Urban"

            every { repository.findById(tablet.id, true) } returns tablet.copy(revokedAt = NOW)
            audit.second.clear()
            service.revoke(tablet.id, OWNER_ACTOR).changed shouldBe false
            audit.second.shouldBeEmpty()
        }
}
