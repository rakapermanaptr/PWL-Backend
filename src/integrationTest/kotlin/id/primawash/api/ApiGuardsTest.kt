package id.primawash.api

import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Cross-cutting guards on `/api/v1`: minimum app version, per-device rate limit, device binding. */
class ApiGuardsTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should ask an outdated app to update but leave health checks alone`() =
        apiTest(settings = ApiTestSupport.settings.copy(minAppVersion = "1.4.0")) { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")

            val old =
                api.client.get("/api/v1/login-options") {
                    bearerAuth(device)
                    header("X-App-Version", "1.3.9")
                }
            old
                .shouldFailWith(HttpStatusCode.UpgradeRequired, "APP_UPDATE_REQUIRED")
                .obj("details")
                .getValue("minAppVersion")
                .jsonPrimitive.content shouldBe "1.4.0"
            api.get("/login-options", device).status shouldBe HttpStatusCode.UpgradeRequired

            val current =
                api.client.get("/api/v1/login-options") {
                    bearerAuth(device)
                    header("X-App-Version", "1.10.0")
                }
            current.status shouldBe HttpStatusCode.OK
            api.client.get("/health").status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `should limit one device to 120 requests a minute with the standard envelope`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            repeat(120) { api.get("/login-options", device).status shouldBe HttpStatusCode.OK }

            val limited = api.get("/login-options", device)
            limited.shouldFailWith(HttpStatusCode.TooManyRequests, "RATE_LIMITED")
            (limited.headers["Retry-After"] != null) shouldBe true

            val otherDevice = ApiTestSupport.registerDevice("Tablet Tebet 2", "TBT")
            api.get("/login-options", otherDevice).status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `should refuse a device id header that does not belong to the token`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val response =
                api.client.get("/api/v1/login-options") {
                    bearerAuth(device)
                    header("X-Device-Id", "00000000-0000-4000-8000-000000000000")
                }
            response.shouldFailWith(HttpStatusCode.Unauthorized, "DEVICE_UNAUTHORIZED")
        }
}
