package id.primawash.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** PIN login, session lifecycle and device binding against the real API and Postgres (PRD §8.1). */
class AuthFlowTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    @Test
    fun `should log a cashier in on their own branch and resolve the session context`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val login = api.login(device, "TBT", "1234")

            login.string("refreshToken") shouldStartWith "rt_"
            login.getValue("accessTokenExpiresIn").jsonPrimitive.content shouldBe "3600"
            login.getValue("refreshTokenExpiresIn").jsonPrimitive.content shouldBe "64800"
            login.obj("context").obj("staff").string("shortName") shouldBe "Siti N."
            login.obj("context").obj("branch").string("code") shouldBe "TBT"

            val me = api.get("/me", login.string("accessToken"))
            me.status shouldBe HttpStatusCode.OK
            me.json().obj("staff").string("name") shouldBe "Siti Nurhaliza"

            ApiTestSupport.auditCount("Login PIN sebagai kasir Tebet di perangkat Cabang Tebet") shouldBe 1
        }

    @Test
    fun `should refuse a cashier on another branch's device and clear the pin`() =
        apiTest { api ->
            // PRD §16 #12
            val device = ApiTestSupport.registerDevice("Tablet Bintaro 1", "BTR")
            val response =
                api.post("/auth/pin-login", """{"branchId":"${ApiTestSupport.branchId("BTR")}","pin":"1234"}""", device)

            val error = response.shouldFailWith(HttpStatusCode.UnprocessableEntity, "STAFF_WRONG_BRANCH")
            error.string("message") shouldBe
                "Siti Nurhaliza terdaftar di Cabang Tebet — tidak bisa login di perangkat Cabang Bintaro."
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
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val body = """{"branchId":"${ApiTestSupport.branchId("TBT")}","pin":"0000"}"""
            repeat(5) {
                api
                    .post(
                        "/auth/pin-login",
                        body,
                        device,
                    ).shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_UNKNOWN")
            }

            val sixth =
                api.post(
                    "/auth/pin-login",
                    """{"branchId":"${ApiTestSupport.branchId("TBT")}","pin":"1234"}""",
                    device,
                )
            sixth.shouldFailWith(HttpStatusCode.Locked, "PIN_LOCKED").string("message") shouldBe
                "Terlalu banyak PIN salah. Coba lagi dalam 5 menit."
            sixth.headers["Retry-After"] shouldBe "300"
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'PIN_LOCKED'") shouldBe 1
        }

    @Test
    fun `should unlock the device once the lock has passed`() {
        val clock = MutableClock()
        apiTest(clock) { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val wrong = """{"branchId":"${ApiTestSupport.branchId("TBT")}","pin":"0000"}"""
            repeat(5) { api.post("/auth/pin-login", wrong, device) }

            clock.advance(
                java.time.Duration
                    .ofMinutes(5)
                    .plusSeconds(1),
            )
            api
                .login(device, "TBT", "1234")
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
            val bagasDevice = ApiTestSupport.registerDevice("Tablet Tebet 2", "TBT")
            val bagas = api.login(bagasDevice, "TBT", "5678").string("accessToken")

            val reset = api.post("/staff/${ApiTestSupport.staffId("Bagas Ardhana")}/reset-pin", token = owner)
            reset.status shouldBe HttpStatusCode.OK
            reset.headers["Cache-Control"] shouldBe "no-store"
            val newPin = reset.json().string("pin")

            api.get("/me", bagas).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api
                .post(
                    "/auth/pin-login",
                    """{"branchId":"${ApiTestSupport.branchId("TBT")}","pin":"5678"}""",
                    bagasDevice,
                ).shouldFailWith(HttpStatusCode.UnprocessableEntity, "PIN_UNKNOWN")
            api
                .login(bagasDevice, "TBT", newPin)
                .obj("context")
                .obj("staff")
                .string("name") shouldBe "Bagas Ardhana"
        }

    @Test
    fun `should rotate the refresh token and refuse the old one`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val login = api.login(device, "TBT", "1234")
            val first = login.string("refreshToken")

            val refreshed = api.post("/auth/refresh", """{"refreshToken":"$first"}""", device)
            refreshed.status shouldBe HttpStatusCode.OK
            val second = refreshed.json().string("refreshToken")
            (second != first) shouldBe true

            api
                .post(
                    "/auth/refresh",
                    """{"refreshToken":"$first"}""",
                    device,
                ).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.post("/auth/refresh", """{"refreshToken":"$second"}""", device).status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `should hand the device to another staff member and end the previous session`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val siti = api.login(device, "TBT", "1234").string("accessToken")

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
            ApiTestSupport.auditCount("Login PIN sebagai kasir Tebet") shouldBe 1
        }

    @Test
    fun `should lock an account after five consecutive wrong pins in verify pin`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            val otherDevice =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 2", "TBT"),
                        "TBT",
                        "5678",
                    ).string("accessToken")
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
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
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
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            api
                .post("/auth/switch-branch", """{"branchId":"${ApiTestSupport.branchId("BTR")}"}""", siti)
                .shouldFailWith(HttpStatusCode.Forbidden, "OWNER_ONLY")
                .string("message") shouldBe
                "Perangkat kasir terikat ke Cabang Tebet. " +
                "Hanya owner/admin yang bisa memindahkan perangkat ke cabang lain."
        }

    @Test
    fun `should move an owner's device to another branch with a new session`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Owner", "TBT")
            val owner = api.ownerToken(device)
            val response =
                api.post(
                    "/auth/switch-branch",
                    """{"branchId":"${ApiTestSupport.branchId("BTR")}"}""",
                    owner,
                )

            response.status shouldBe HttpStatusCode.OK
            val body = response.json()
            body.getValue("changed").jsonPrimitive.boolean shouldBe true
            api
                .get("/me", body.string("accessToken"))
                .json()
                .obj("branch")
                .string("code") shouldBe "BTR"
            api.get("/me", owner).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.get("/login-options", device).json().string("lastBranchId") shouldBe
                ApiTestSupport.branchId("BTR").toString()
        }

    @Test
    fun `should log out and return the branch to preselect`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            val response = api.post("/auth/logout", token = siti)

            response.json().string("lastBranchId") shouldBe ApiTestSupport.branchId("TBT").toString()
            api.get("/me", siti).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
        }

    @Test
    fun `should refuse a staff token on a device endpoint and a device token on a staff endpoint`() =
        apiTest { api ->
            val device = ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT")
            val siti = api.login(device, "TBT", "1234").string("accessToken")

            api.get("/me", device).shouldFailWith(HttpStatusCode.Unauthorized, "UNAUTHENTICATED")
            api.get("/login-options", siti).shouldFailWith(HttpStatusCode.Unauthorized, "DEVICE_UNAUTHORIZED")
            api.get("/login-options").shouldFailWith(HttpStatusCode.Unauthorized, "DEVICE_UNAUTHORIZED")
        }
}
