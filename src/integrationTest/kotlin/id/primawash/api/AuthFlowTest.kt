package id.primawash.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Login screen, PIN login and the session lifecycle against the real API and Postgres (PRD §8.1). */
class AuthFlowTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should show the login screen to a fresh install without any token`() =
        apiTest { api ->
            val options = api.get("/login-options")
            options.status shouldBe HttpStatusCode.OK
            val body = options.json()
            body.getValue("lastBranchId").toString() shouldBe "null"
            body.getValue("branches").jsonArray.size shouldBe 3
        }

    @Test
    fun `should log a cashier in with just a branch and a pin`() =
        apiTest { api ->
            val device = ApiTestSupport.newDevice()
            val login = api.login(device, "FMU", "1234")

            login.string("refreshToken") shouldStartWith "rt_"
            login.getValue("accessTokenExpiresIn").jsonPrimitive.content shouldBe "3600"
            login.getValue("refreshTokenExpiresIn").jsonPrimitive.content shouldBe "64800"
            login.obj("context").obj("staff").string("shortName") shouldBe "Siti N."
            login.obj("context").obj("branch").string("code") shouldBe "FMU"

            val me = api.get("/me", login.string("accessToken"))
            me.status shouldBe HttpStatusCode.OK
            me.json().obj("staff").string("name") shouldBe "Siti Nurhaliza"

            ApiTestSupport.auditCount(
                "Login PIN sebagai kasir Familia Urban di perangkat Cabang Familia Urban",
            ) shouldBe
                1
            api.get("/login-options", deviceId = device).json().string("lastBranchId") shouldBe
                ApiTestSupport.branchId("FMU").toString()
        }

    @Test
    fun `should require the installation id on pin login`() =
        apiTest { api ->
            api
                .post("/auth/pin-login", """{"branchId":"${ApiTestSupport.branchId("FMU")}","pin":"1234"}""")
                .shouldFailWith(HttpStatusCode.BadRequest, "VALIDATION_ERROR")
        }

    @Test
    fun `should refuse a cashier on another branch's device and clear the pin`() =
        apiTest { api ->
            // PRD §16 #12
            val error =
                api
                    .pinLogin(ApiTestSupport.newDevice(), "NRG", "1234")
                    .shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_WRONG_BRANCH")
            error.string("message") shouldBe
                "Siti Nurhaliza terdaftar di Cabang Familia Urban — tidak bisa login di perangkat Cabang Narogong."
            error
                .obj("details")
                .getValue("clearPin")
                .jsonPrimitive.boolean shouldBe true
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'LOGIN_FAILED'") shouldBe 1
        }

    @Test
    fun `should lock the device after five wrong pins`() =
        apiTest { api ->
            // PRD §16 #14
            val device = ApiTestSupport.newDevice()
            repeat(5) {
                api.pinLogin(device, "FMU", "0000").shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_UNKNOWN")
            }

            val sixth = api.pinLogin(device, "FMU", "1234")
            sixth.shouldFailWith(HttpStatusCode.Locked, "PIN_LOCKED").string("message") shouldBe
                "Terlalu banyak PIN salah. Coba lagi dalam 5 menit."
            sixth.headers["Retry-After"] shouldBe "300"
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'PIN_LOCKED'") shouldBe 1

            // Another tablet in the same shop is not affected.
            api
                .login(
                    ApiTestSupport.newDevice(),
                    "FMU",
                    "1234",
                ).obj("context")
                .obj("staff")
                .string("shortName") shouldBe
                "Siti N."
        }

    @Test
    fun `should unlock the device once the lock has passed`() {
        val clock = MutableClock()
        apiTest(clock) { api ->
            val device = ApiTestSupport.newDevice()
            repeat(5) { api.pinLogin(device, "FMU", "0000") }

            clock.advance(
                java.time.Duration
                    .ofMinutes(5)
                    .plusSeconds(1),
            )
            api
                .login(device, "FMU", "1234")
                .obj("context")
                .obj("staff")
                .string("shortName") shouldBe "Siti N."
        }
    }

    @Test
    fun `should end the session of a staff member whose pin was reset`() =
        apiTest { api ->
            // PRD §16 #13
            val owner = api.ownerToken()
            val bagasDevice = ApiTestSupport.newDevice()
            val bagas = api.login(bagasDevice, "FMU", "5678").string("accessToken")

            val reset = api.post("/staff/${ApiTestSupport.staffId("Bagas Ardhana")}/reset-pin", token = owner)
            reset.status shouldBe HttpStatusCode.OK
            reset.headers["Cache-Control"] shouldBe "no-store"
            val newPin = reset.json().string("pin")

            api.get("/me", bagas).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.pinLogin(bagasDevice, "FMU", "5678").shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_UNKNOWN")
            api
                .login(bagasDevice, "FMU", newPin)
                .obj("context")
                .obj("staff")
                .string("name") shouldBe "Bagas Ardhana"
        }

    @Test
    fun `should rotate the refresh token and accept it only from the same tablet`() =
        apiTest { api ->
            val device = ApiTestSupport.newDevice()
            val first = api.login(device, "FMU", "1234").string("refreshToken")

            val refreshed = api.post("/auth/refresh", """{"refreshToken":"$first"}""", deviceId = device)
            refreshed.status shouldBe HttpStatusCode.OK
            val second = refreshed.json().string("refreshToken")
            (second != first) shouldBe true

            api
                .post("/auth/refresh", """{"refreshToken":"$first"}""", deviceId = device)
                .shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api
                .post("/auth/refresh", """{"refreshToken":"$second"}""", deviceId = ApiTestSupport.newDevice())
                .shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.post("/auth/refresh", """{"refreshToken":"$second"}""", deviceId = device).status shouldBe
                HttpStatusCode.OK
        }

    @Test
    fun `should hand the device to another staff member and end the previous session`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")

            val switched =
                api.post(
                    "/auth/switch-staff",
                    """{"staffId":"${ApiTestSupport.staffId("Bagas Ardhana")}","pin":"5678"}""",
                    siti,
                )
            switched.status shouldBe HttpStatusCode.OK
            val bagas = switched.json().string("accessToken")

            api.get("/me", siti).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api
                .get("/me", bagas)
                .json()
                .obj("staff")
                .string("shortName") shouldBe "Bagas A."
            ApiTestSupport.auditCount("Login PIN sebagai kasir Familia Urban") shouldBe 1
        }

    @Test
    fun `should keep one session per tablet`() =
        apiTest { api ->
            val device = ApiTestSupport.newDevice()
            val siti = api.login(device, "FMU", "1234").string("accessToken")
            api.login(device, "FMU", "5678")

            api.get("/me", siti).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
        }

    @Test
    fun `should lock an account after five consecutive wrong pins in verify pin`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")
            val otherDevice = api.login(ApiTestSupport.newDevice(), "FMU", "5678").string("accessToken")
            val bagasId = ApiTestSupport.staffId("Bagas Ardhana")

            // Spread over two tablets so the per-device limit does not trigger first.
            repeat(3) {
                api
                    .post("/auth/verify-pin", """{"staffId":"$bagasId","pin":"1111"}""", siti)
                    .shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_WRONG")
            }
            repeat(2) {
                api
                    .post("/auth/verify-pin", """{"staffId":"$bagasId","pin":"1111"}""", otherDevice)
                    .shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_WRONG")
            }

            api
                .post("/auth/verify-pin", """{"staffId":"$bagasId","pin":"5678"}""", siti)
                .shouldFailWith(HttpStatusCode.Locked, "PIN_LOCKED")
                .string("message") shouldBe "Terlalu banyak PIN salah. Coba lagi dalam 15 menit."
        }

    @Test
    fun `should issue a single use staff proof for a correct pin`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")
            val response =
                api.post(
                    "/auth/verify-pin",
                    """{"staffId":"${ApiTestSupport.staffId("Bagas Ardhana")}","pin":"5678"}""",
                    siti,
                )

            response.status shouldBe HttpStatusCode.OK
            val body = response.json()
            body.string("staffProof") shouldStartWith "sp_"
            body.getValue("expiresIn").jsonPrimitive.content shouldBe "300"
            ApiTestSupport.count("SELECT count(*) FROM staff_proofs WHERE used_at IS NULL") shouldBe 1
        }

    @Test
    fun `should keep a cashier's device on their branch`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")
            api
                .post("/auth/switch-branch", """{"branchId":"${ApiTestSupport.branchId("NRG")}"}""", siti)
                .shouldFailWith(HttpStatusCode.Forbidden, "OWNER_ONLY")
                .string("message") shouldBe
                "Perangkat kasir terikat ke Cabang Familia Urban. " +
                "Hanya owner/admin yang bisa memindahkan perangkat ke cabang lain."
        }

    @Test
    fun `should move an owner's device to another branch with a new session`() =
        apiTest { api ->
            val device = ApiTestSupport.newDevice()
            val owner = api.ownerToken(device)
            val response =
                api.post(
                    "/auth/switch-branch",
                    """{"branchId":"${ApiTestSupport.branchId("NRG")}"}""",
                    owner,
                )

            response.status shouldBe HttpStatusCode.OK
            val body = response.json()
            body.getValue("changed").jsonPrimitive.boolean shouldBe true
            api
                .get("/me", body.string("accessToken"))
                .json()
                .obj("branch")
                .string("code") shouldBe "NRG"
            api.get("/me", owner).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.get("/login-options", deviceId = device).json().string("lastBranchId") shouldBe
                ApiTestSupport.branchId("NRG").toString()
        }

    @Test
    fun `should log out and return the branch to preselect`() =
        apiTest { api ->
            val siti = api.login(ApiTestSupport.newDevice(), "FMU", "1234").string("accessToken")
            val response = api.post("/auth/logout", token = siti)

            response.json().string("lastBranchId") shouldBe ApiTestSupport.branchId("FMU").toString()
            api.get("/me", siti).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
        }

    @Test
    fun `should refuse data endpoints without an access token`() =
        apiTest { api ->
            api.get("/me").shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.get("/customers", token = "bukan-token").shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
        }
}
