package id.primawash.api

import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Tablets without an activation step: recorded on first login, listed and blockable by the owner. */
class DeviceFlowTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should record a tablet on its first login and audit it once`() =
        apiTest { api ->
            val device = ApiTestSupport.newDevice()
            api.login(device, "NRG", "2468")
            api.login(device, "NRG", "1357")

            val owner = api.ownerToken()
            val items =
                api
                    .get("/devices", owner)
                    .json()
                    .getValue("items")
                    .jsonArray
                    .map { it.jsonObject }
            val tablet = items.first { it.string("id") == device }
            tablet.string("name") shouldBe "Tablet Cabang Narogong"
            tablet.string("lastBranchId") shouldBe ApiTestSupport.branchId("NRG").toString()
            ApiTestSupport.auditCount("Tablet baru dipakai login pertama kali di Cabang Narogong") shouldBe 1
        }

    @Test
    fun `should not list installations that never logged in`() =
        apiTest { api ->
            repeat(3) { api.pinLogin(ApiTestSupport.newDevice(), "FMU", "0000") }
            val owner = api.ownerToken()

            api
                .get("/devices", owner)
                .json()
                .getValue("items")
                .jsonArray.size shouldBe 1
        }

    @Test
    fun `should block a lost tablet and end its sessions`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val tablet = ApiTestSupport.newDevice()
            val login = api.login(tablet, "FMU", "1234")

            val blocked = api.delete("/devices/$tablet", owner)
            blocked.status shouldBe HttpStatusCode.OK
            blocked
                .json()
                .getValue("changed")
                .jsonPrimitive.content shouldBe "true"

            api.get("/me", login.string("accessToken")).status shouldBe HttpStatusCode.Unauthorized
            api
                .pinLogin(tablet, "FMU", "1234")
                .shouldFailWith(HttpStatusCode.Unauthorized, "DEVICE_UNAUTHORIZED")
                .string("message") shouldBe "Tablet ini sudah diblokir owner — hubungi owner untuk memakai tablet lain."
            api
                .post("/auth/refresh", """{"refreshToken":"${login.string("refreshToken")}"}""", deviceId = tablet)
                .status shouldBe HttpStatusCode.Unauthorized
            ApiTestSupport.auditCount("Blokir perangkat Tablet Cabang Familia Urban") shouldBe 1
        }

    @Test
    fun `should keep device management away from cashiers`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")
            api.get("/devices", siti).shouldFailWith(HttpStatusCode.Forbidden, "FORBIDDEN")
        }
}
