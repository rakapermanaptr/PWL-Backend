package id.primawash.api

import id.primawash.api.plugins.PIN_LOGINS_PER_ADDRESS
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Cross-cutting guards on `/api/v1`: minimum app version, rate limits, device binding of a session. */
class ApiGuardsTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should ask an outdated app to update but leave health checks alone`() =
        apiTest(settings = ApiTestSupport.settings.copy(minAppVersion = "1.4.0")) { api ->
            val old = api.client.get("/api/v1/login-options") { header("X-App-Version", "1.3.9") }
            old
                .shouldFailWith(HttpStatusCode.UpgradeRequired, "APP_UPDATE_REQUIRED")
                .obj("details")
                .getValue("minAppVersion")
                .jsonPrimitive.content shouldBe "1.4.0"
            api.get("/login-options").status shouldBe HttpStatusCode.UpgradeRequired

            api.client.get("/api/v1/login-options") { header("X-App-Version", "1.10.0") }.status shouldBe
                HttpStatusCode.OK
            api.client.get("/health").status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `should limit one device to 120 requests a minute with the standard envelope`() =
        apiTest { api ->
            val device = ApiTestSupport.newDevice()
            repeat(120) { api.get("/login-options", deviceId = device).status shouldBe HttpStatusCode.OK }

            val limited = api.get("/login-options", deviceId = device)
            limited.shouldFailWith(HttpStatusCode.TooManyRequests, "RATE_LIMITED")
            (limited.headers["Retry-After"] != null) shouldBe true

            api.get("/login-options", deviceId = ApiTestSupport.newDevice()).status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `should cap pin attempts from one address even with a new installation id each time`() =
        apiTest { api ->
            repeat(PIN_LOGINS_PER_ADDRESS) {
                api.pinLogin(ApiTestSupport.newDevice(), "FMU", "0000").status shouldBe
                    HttpStatusCode.UnprocessableEntity
            }
            api
                .pinLogin(
                    ApiTestSupport.newDevice(),
                    "FMU",
                    "9090",
                ).shouldFailWith(HttpStatusCode.TooManyRequests, "RATE_LIMITED")
        }

    @Test
    fun `should refuse a device id header that does not belong to the session`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")
            api
                .get("/me", token = siti, deviceId = ApiTestSupport.newDevice())
                .shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
        }
}
