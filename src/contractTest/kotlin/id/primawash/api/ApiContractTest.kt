package id.primawash.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Every endpoint needs a contract test against `openapi.yaml` (CLAUDE.md). One walk through the whole
 * M1 surface — owner and cashier, success and the documented errors — validates each response body
 * against the schema declared for its status, then checks that no documented operation was skipped
 * and that the server serves nothing the document does not describe.
 */
class ApiContractTest {
    private val exercised = mutableSetOf<Operation>()

    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private suspend fun HttpResponse.conformsTo(
        method: String,
        path: String,
        expected: HttpStatusCode,
    ): HttpResponse {
        status shouldBe expected
        OpenApiContract.validate(Operation(method, path), status.value, bodyAsText())
        exercised += Operation(method, path)
        return this
    }

    // One deliberate walk-through: later steps need the ids and tokens earlier steps create.
    @Suppress("LongMethod")
    @Test
    fun `should match openapi yaml for every documented operation`() =
        apiTest { api ->
            val tebet = ApiTestSupport.branchId("TBT")
            val bintaro = ApiTestSupport.branchId("BTR")

            api.client.get("/health").conformsTo("GET", "/health", HttpStatusCode.OK)
            api.client.get("/health/ready").conformsTo("GET", "/health/ready", HttpStatusCode.OK)

            // ---- Owner: tablet, login, devices ----------------------------------------------
            val ownerDevice = ApiTestSupport.registerDevice("Tablet Owner", "TBT")
            api.get("/login-options", ownerDevice).conformsTo("GET", "/api/v1/login-options", HttpStatusCode.OK)
            api.get("/login-options").conformsTo("GET", "/api/v1/login-options", HttpStatusCode.Unauthorized)
            val ownerLogin =
                api
                    .post("/auth/pin-login", """{"branchId":"$tebet","pin":"9090"}""", ownerDevice)
                    .conformsTo("POST", "/api/v1/auth/pin-login", HttpStatusCode.OK)
                    .json()
            val owner = ownerLogin.string("accessToken")
            api
                .post("/auth/pin-login", """{"branchId":"$tebet","pin":"12"}""", ownerDevice)
                .conformsTo("POST", "/api/v1/auth/pin-login", HttpStatusCode.UnprocessableEntity)
            api
                .post("/auth/refresh", """{"refreshToken":"${ownerLogin.string("refreshToken")}"}""", ownerDevice)
                .conformsTo("POST", "/api/v1/auth/refresh", HttpStatusCode.OK)
            api.get("/me", owner).conformsTo("GET", "/api/v1/me", HttpStatusCode.OK)
            api.get("/me").conformsTo("GET", "/api/v1/me", HttpStatusCode.Unauthorized)

            val code =
                api
                    .post("/devices/activation-codes", """{"branchId":"$bintaro"}""", owner)
                    .conformsTo("POST", "/api/v1/devices/activation-codes", HttpStatusCode.Created)
                    .json()
                    .string("code")
            val activated =
                api
                    .post("/devices/activate", """{"code":"$code","name":"Tablet Bintaro 1","appVersion":"1.4.0"}""")
                    .conformsTo("POST", "/api/v1/devices/activate", HttpStatusCode.Created)
                    .json()
            api
                .post("/devices/activate", """{"code":"$code"}""")
                .conformsTo("POST", "/api/v1/devices/activate", HttpStatusCode.UnprocessableEntity)
            val bintaroDevice = activated.string("deviceToken")
            val devices =
                api
                    .get("/devices", owner)
                    .conformsTo("GET", "/api/v1/devices", HttpStatusCode.OK)
                    .json()
                    .getValue("items")
                    .jsonArray
            devices.size shouldBe 2

            // ---- Branches -------------------------------------------------------------------
            api.get("/branches", owner).conformsTo("GET", "/api/v1/branches", HttpStatusCode.OK)
            api.get("/branches/overview", owner).conformsTo("GET", "/api/v1/branches/overview", HttpStatusCode.OK)
            api
                .patch("/branches/$tebet", """{"dailyTarget":5500000}""", owner)
                .conformsTo("PATCH", "/api/v1/branches/{id}", HttpStatusCode.OK)
            api
                .patch("/branches/$tebet", """{"dailyTarget":0}""", owner)
                .conformsTo("PATCH", "/api/v1/branches/{id}", HttpStatusCode.UnprocessableEntity)

            // ---- Staff ----------------------------------------------------------------------
            api.get("/staff", owner).conformsTo("GET", "/api/v1/staff", HttpStatusCode.OK)
            val dimas =
                api
                    .post(
                        "/staff",
                        """{"name":"Dimas Pratama","pin":"4321","role":"KASIR","branchId":"$bintaro"}""",
                        owner,
                    ).conformsTo("POST", "/api/v1/staff", HttpStatusCode.Created)
                    .json()
                    .obj("staff")
                    .string("id")
            api
                .post("/staff", """{"name":"Dimas Lagi","pin":"4321","role":"KASIR","branchId":"$bintaro"}""", owner)
                .conformsTo("POST", "/api/v1/staff", HttpStatusCode.UnprocessableEntity)
            api
                .patch("/staff/${ApiTestSupport.staffId("Yuni Astari")}", """{"active":true}""", owner)
                .conformsTo("PATCH", "/api/v1/staff/{id}", HttpStatusCode.Conflict)
            api
                .post("/staff/$dimas/reset-pin", token = owner)
                .conformsTo("POST", "/api/v1/staff/{id}/reset-pin", HttpStatusCode.OK)
            api
                .patch("/staff/$dimas", """{"active":false}""", owner)
                .conformsTo("PATCH", "/api/v1/staff/{id}", HttpStatusCode.OK)

            // ---- Catalog --------------------------------------------------------------------
            val services =
                api
                    .get("/services?includeInactive=true", owner)
                    .conformsTo("GET", "/api/v1/services", HttpStatusCode.OK)
                    .json()
                    .getValue("items")
                    .jsonArray
            val cuciSetrika = services.first { it.jsonObject.string("name") == "Cuci Setrika" }.jsonObject.string("id")
            val created =
                api
                    .post(
                        "/services",
                        """{"category":"SATUAN","name":"Tas Ransel","price":30000,"unit":"pcs"}""",
                        owner,
                    ).conformsTo("POST", "/api/v1/services", HttpStatusCode.Created)
                    .json()
                    .obj("service")
                    .string("id")
            api
                .patch("/services/prices", """{"prices":{"$cuciSetrika":11000}}""", owner)
                .conformsTo("PATCH", "/api/v1/services/prices", HttpStatusCode.OK)
            api
                .patch("/services/$created", """{"active":false}""", owner)
                .conformsTo("PATCH", "/api/v1/services/{id}", HttpStatusCode.OK)
            api.get("/loyalty/rate", owner).conformsTo("GET", "/api/v1/loyalty/rate", HttpStatusCode.OK)
            api
                .put("/loyalty/rate", """{"rupiahPerStep":10000,"pointsPerStep":150}""", owner)
                .conformsTo("PUT", "/api/v1/loyalty/rate", HttpStatusCode.OK)
            api.get("/rewards?includeInactive=true", owner).conformsTo("GET", "/api/v1/rewards", HttpStatusCode.OK)
            val reward =
                api
                    .post(
                        "/rewards",
                        """{"name":"Diskon Rp50.000","cost":5000,"value":50000,"minSubtotal":150000}""",
                        owner,
                    ).conformsTo("POST", "/api/v1/rewards", HttpStatusCode.Created)
                    .json()
                    .obj("reward")
                    .string("id")
            api
                .patch("/rewards/$reward", """{"active":false}""", owner)
                .conformsTo("PATCH", "/api/v1/rewards/{id}", HttpStatusCode.OK)

            // ---- Cashier --------------------------------------------------------------------
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            api.get("/staff", siti).conformsTo("GET", "/api/v1/staff", HttpStatusCode.OK)
            api.get("/staff?branchId=$bintaro", siti).conformsTo("GET", "/api/v1/staff", HttpStatusCode.Forbidden)
            api.get("/devices", siti).conformsTo("GET", "/api/v1/devices", HttpStatusCode.Forbidden)
            api
                .post(
                    "/auth/verify-pin",
                    """{"staffId":"${ApiTestSupport.staffId("Bagas Ardhana")}","pin":"5678"}""",
                    siti,
                ).conformsTo("POST", "/api/v1/auth/verify-pin", HttpStatusCode.OK)
            api
                .post(
                    "/auth/verify-pin",
                    """{"staffId":"${ApiTestSupport.staffId("Bagas Ardhana")}","pin":"0000"}""",
                    siti,
                ).conformsTo("POST", "/api/v1/auth/verify-pin", HttpStatusCode.UnprocessableEntity)
            api
                .post("/auth/switch-branch", """{"branchId":"$bintaro"}""", siti)
                .conformsTo("POST", "/api/v1/auth/switch-branch", HttpStatusCode.Forbidden)

            // ---- Customers ------------------------------------------------------------------
            val dewi =
                api
                    .post("/customers", """{"name":"Dewi Anggraini","phone":"0812-3390-4471","optIn":true}""", siti)
                    .conformsTo("POST", "/api/v1/customers", HttpStatusCode.Created)
                    .json()
                    .obj("customer")
                    .string("id")
            api
                .post("/customers", """{"name":"Dewi A.","phone":"081233904471","optIn":false}""", siti)
                .conformsTo("POST", "/api/v1/customers", HttpStatusCode.Conflict)
            api.get("/customers?q=dewi&limit=10", siti).conformsTo("GET", "/api/v1/customers", HttpStatusCode.OK)
            api.get("/customers/$dewi", siti).conformsTo("GET", "/api/v1/customers/{id}", HttpStatusCode.OK)
            api
                .get("/customers/${ApiTestSupport.staffId("Siti Nurhaliza")}", siti)
                .conformsTo("GET", "/api/v1/customers/{id}", HttpStatusCode.NotFound)
            api
                .patch("/customers/$dewi", """{"optIn":false}""", siti)
                .conformsTo("PATCH", "/api/v1/customers/{id}", HttpStatusCode.OK)
            api
                .get("/customers/$dewi/points", siti)
                .conformsTo("GET", "/api/v1/customers/{id}/points", HttpStatusCode.OK)

            // ---- Hand-over and exits --------------------------------------------------------
            val bagas =
                api
                    .post(
                        "/auth/switch-staff",
                        """{"staffId":"${ApiTestSupport.staffId("Bagas Ardhana")}","pin":"5678"}""",
                        siti,
                    ).conformsTo("POST", "/api/v1/auth/switch-staff", HttpStatusCode.OK)
                    .json()
                    .string("accessToken")
            api
                .post("/auth/switch-branch", """{"branchId":"$bintaro"}""", owner)
                .conformsTo("POST", "/api/v1/auth/switch-branch", HttpStatusCode.OK)
            api.post("/auth/logout", token = bagas).conformsTo("POST", "/api/v1/auth/logout", HttpStatusCode.OK)
            val tabletId = devices.first { it.jsonObject.string("name") == "Tablet Bintaro 1" }.jsonObject.string("id")
            val ownerAgain = api.login(ownerDevice, "TBT", "9090").string("accessToken")
            api
                .delete("/devices/$tabletId", ownerAgain)
                .conformsTo("DELETE", "/api/v1/devices/{id}", HttpStatusCode.OK)
            api
                .post("/auth/refresh", """{"refreshToken":"rt_tidak-ada"}""", ownerDevice)
                .conformsTo("POST", "/api/v1/auth/refresh", HttpStatusCode.Unauthorized)
            api
                .get(
                    "/login-options",
                    bintaroDevice,
                ).conformsTo("GET", "/api/v1/login-options", HttpStatusCode.Unauthorized)

            (OpenApiContract.operations() - exercised).shouldBeEmpty()
        }

    @Test
    fun `should serve nothing that openapi yaml does not document`() =
        testApplication {
            application { apiModule(PostgresSupport.dataSource, ApiTestSupport.database, ApiTestSupport.settings) }
            startApplication()

            val served =
                application
                    .plugin(RoutingRoot)
                    .getAllRoutes()
                    .mapNotNull { it.operation() }
                    .toSet()

            (served - OpenApiContract.operations()).shouldBeEmpty()
            (OpenApiContract.operations() - served).shouldBeEmpty()
        }

    private fun RoutingNode.operation(): Operation? {
        val method = (selector as? HttpMethodRouteSelector)?.method?.value ?: return null
        val segments =
            generateSequence(this) { it.parent }
                .toList()
                .reversed()
                .mapNotNull { node ->
                    when (val s = node.selector) {
                        is PathSegmentConstantRouteSelector -> s.value
                        is PathSegmentParameterRouteSelector -> "{${s.name}}"
                        else -> null
                    }
                }
        return Operation(method, "/" + segments.joinToString("/"))
    }
}
