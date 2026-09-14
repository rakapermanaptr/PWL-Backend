package id.primawash.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Activation codes, activation and revocation (PRD §8.1, §12.1). */
class DeviceFlowTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should activate a tablet with an owner's code and let a cashier log in on it`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val issued =
                api.post("/devices/activation-codes", """{"branchId":"${ApiTestSupport.branchId("BTR")}"}""", owner)
            issued.status shouldBe HttpStatusCode.Created
            issued.headers["Cache-Control"] shouldBe "no-store"
            val code = issued.json().string("code")
            code shouldMatch Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$")

            val activated = api.post("/devices/activate", """{"code":"${code.lowercase()}","appVersion":"1.4.0"}""")
            activated.status shouldBe HttpStatusCode.Created
            val body = activated.json()
            body.obj("device").string("name") shouldBe "Tablet Cabang Bintaro"
            val deviceToken = body.string("deviceToken")

            api.get("/login-options", deviceToken).json().string("lastBranchId") shouldBe
                ApiTestSupport.branchId("BTR").toString()
            api
                .login(deviceToken, "BTR", "2468")
                .obj("context")
                .obj("staff")
                .string("shortName") shouldBe "Nia R."
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'DEVICE_ACTIVATED'") shouldBe 1
        }

    @Test
    fun `should accept an activation code only once`() =
        apiTest { api ->
            val code = api.post("/devices/activation-codes", token = api.ownerToken()).json().string("code")
            api.post("/devices/activate", """{"code":"$code"}""").status shouldBe HttpStatusCode.Created

            api
                .post("/devices/activate", """{"code":"$code"}""")
                .shouldFailWith(HttpStatusCode.UnprocessableEntity, "ACTIVATION_CODE_INVALID")
        }

    @Test
    fun `should end every session on a revoked tablet`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val tablet = ApiTestSupport.registerDevice("Tablet Tebet 2", "TBT")
            val siti = api.login(tablet, "TBT", "1234").string("accessToken")
            val tabletId = ApiTestSupport.uuidOf("SELECT id FROM devices WHERE name = 'Tablet Tebet 2'")

            val revoked = api.delete("/devices/$tabletId", owner)
            revoked.status shouldBe HttpStatusCode.OK
            revoked
                .json()
                .getValue("changed")
                .jsonPrimitive.content shouldBe "true"

            api.get("/me", siti).status shouldBe HttpStatusCode.Unauthorized
            api.get("/login-options", tablet).shouldFailWith(HttpStatusCode.Unauthorized, "DEVICE_UNAUTHORIZED")
        }

    @Test
    fun `should keep device management away from cashiers`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            api.get("/devices", siti).shouldFailWith(HttpStatusCode.Forbidden, "FORBIDDEN")
            api.post("/devices/activation-codes", token = siti).shouldFailWith(HttpStatusCode.Forbidden, "FORBIDDEN")
        }
}
