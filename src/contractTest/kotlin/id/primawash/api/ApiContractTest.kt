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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Every endpoint needs a contract test against `openapi.yaml` (CLAUDE.md). One walk through the whole
 * API surface — owner and cashier, success and the documented errors — validates each response body
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

            // ---- Login screen and owner session ----------------------------------------------
            val ownerDevice = ApiTestSupport.newDevice()
            api.get("/login-options").conformsTo("GET", "/api/v1/login-options", HttpStatusCode.OK)
            val ownerLogin =
                api
                    .post("/auth/pin-login", """{"branchId":"$tebet","pin":"9090"}""", deviceId = ownerDevice)
                    .conformsTo("POST", "/api/v1/auth/pin-login", HttpStatusCode.OK)
                    .json()
            val owner = ownerLogin.string("accessToken")
            api
                .post("/auth/pin-login", """{"branchId":"$tebet","pin":"12"}""", deviceId = ownerDevice)
                .conformsTo("POST", "/api/v1/auth/pin-login", HttpStatusCode.UnprocessableEntity)
            api
                .post("/auth/pin-login", """{"branchId":"$tebet","pin":"9090"}""")
                .conformsTo("POST", "/api/v1/auth/pin-login", HttpStatusCode.BadRequest)
            val refreshed =
                api
                    .post(
                        "/auth/refresh",
                        """{"refreshToken":"${ownerLogin.string("refreshToken")}"}""",
                        deviceId = ownerDevice,
                    ).conformsTo("POST", "/api/v1/auth/refresh", HttpStatusCode.OK)
                    .json()
            val ownerToken = refreshed.string("accessToken")
            api.get("/me", ownerToken).conformsTo("GET", "/api/v1/me", HttpStatusCode.OK)
            api.get("/me").conformsTo("GET", "/api/v1/me", HttpStatusCode.Unauthorized)

            val bintaroDevice = ApiTestSupport.newDevice()
            api.login(bintaroDevice, "BTR", "2468")
            api
                .get("/login-options", deviceId = bintaroDevice)
                .conformsTo("GET", "/api/v1/login-options", HttpStatusCode.OK)
            api.get("/devices", ownerToken).conformsTo("GET", "/api/v1/devices", HttpStatusCode.OK)

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
                        ApiTestSupport.newDevice(),
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

            walkTransactions(api, siti, owner, dewi, cuciSetrika, created)

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
            val ownerAgain = api.login(ownerDevice, "TBT", "9090").string("accessToken")
            api
                .delete("/devices/$bintaroDevice", ownerAgain)
                .conformsTo("DELETE", "/api/v1/devices/{id}", HttpStatusCode.OK)
            api
                .post("/auth/pin-login", """{"branchId":"$bintaro","pin":"2468"}""", deviceId = bintaroDevice)
                .conformsTo("POST", "/api/v1/auth/pin-login", HttpStatusCode.Unauthorized)
            api
                .post("/auth/refresh", """{"refreshToken":"rt_tidak-ada"}""", deviceId = ownerDevice)
                .conformsTo("POST", "/api/v1/auth/refresh", HttpStatusCode.Unauthorized)

            (OpenApiContract.operations() - exercised).shouldBeEmpty()
        }

    /** The M2 surface, walked as one cashier's day; a separate method only to stay under the JVM method size limit. */
    @Suppress("LongMethod")
    private suspend fun walkTransactions(
        api: TestApi,
        siti: String,
        owner: String,
        dewi: String,
        cuciSetrika: String,
        inactiveService: String,
    ) {
        val tebet = ApiTestSupport.branchId("TBT")
        val bintaro = ApiTestSupport.branchId("BTR")
        val created = inactiveService
        // ---- Shift, orders, offline queue, tablet cache (M2) ----------------------------------------
        api.get("/shifts/current", siti).conformsTo("GET", "/api/v1/shifts/current", HttpStatusCode.OK)
        api
            .get("/shifts/current?branchId=$bintaro", siti)
            .conformsTo("GET", "/api/v1/shifts/current", HttpStatusCode.Forbidden)
        val walkIn = Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "2", 11_000)), 22_000)
        api
            .post("/orders", walkIn, siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/orders", HttpStatusCode.UnprocessableEntity)
        val sitiProof = proofFor(api, siti, "Siti Nurhaliza", "1234")
        api
            .post("/shifts", """{"openingCash":0,"staffProof":"$sitiProof"}""", siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/shifts", HttpStatusCode.UnprocessableEntity)
        api
            .post("/shifts", """{"openingCash":500000,"staffProof":"$sitiProof"}""", siti)
            .conformsTo("POST", "/api/v1/shifts", HttpStatusCode.BadRequest)
        val shift =
            api
                .post(
                    "/shifts",
                    """{"openingCash":500000,"staffProof":"$sitiProof"}""",
                    siti,
                    idempotencyKey = Tx.key(),
                ).conformsTo("POST", "/api/v1/shifts", HttpStatusCode.Created)
                .json()
                .obj("shift")
                .string("id")
        val secondProof = proofFor(api, siti, "Siti Nurhaliza", "1234")
        api
            .post("/shifts", """{"openingCash":500000,"staffProof":"$secondProof"}""", siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/shifts", HttpStatusCode.Conflict)
        api.get("/shifts/latest", siti).conformsTo("GET", "/api/v1/shifts/latest", HttpStatusCode.OK)
        api.get("/shifts", siti).conformsTo("GET", "/api/v1/shifts", HttpStatusCode.Forbidden)
        api.get("/shifts?limit=1", owner).conformsTo("GET", "/api/v1/shifts", HttpStatusCode.OK)
        api.get("/shifts?from=kemarin", owner).conformsTo("GET", "/api/v1/shifts", HttpStatusCode.BadRequest)

        api.get("/orders/next-number", siti).conformsTo("GET", "/api/v1/orders/next-number", HttpStatusCode.OK)
        val order =
            api
                .post(
                    "/orders",
                    Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "4.5", 11_000)), 49_500, customerId = dewi),
                    siti,
                    idempotencyKey = Tx.key(),
                ).conformsTo("POST", "/api/v1/orders", HttpStatusCode.Created)
                .json()
                .obj("order")
                .string("id")
        api
            .post("/orders", walkIn, siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/orders", HttpStatusCode.Created)
        api
            .post(
                "/orders",
                Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "1", 10_000)), 10_000),
                siti,
                idempotencyKey = Tx.key(),
            ).conformsTo("POST", "/api/v1/orders", HttpStatusCode.Conflict)
        api
            .post(
                "/orders",
                Tx.orderBody(Tx.key(), listOf(Line(cuciSetrika, "1", 11_000)), 11_000, customerId = tebet.toString()),
                siti,
                idempotencyKey = Tx.key(),
            ).conformsTo("POST", "/api/v1/orders", HttpStatusCode.NotFound)
        api
            .post("/orders", """{"items":[]}""", siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/orders", HttpStatusCode.BadRequest)
        api.get("/orders?q=dewi", siti).conformsTo("GET", "/api/v1/orders", HttpStatusCode.OK)
        api.get("/orders?status=HILANG", siti).conformsTo("GET", "/api/v1/orders", HttpStatusCode.BadRequest)
        api.get("/orders/$order", siti).conformsTo("GET", "/api/v1/orders/{id}", HttpStatusCode.OK)
        api.get("/orders/$shift", siti).conformsTo("GET", "/api/v1/orders/{id}", HttpStatusCode.NotFound)
        api
            .post("/orders/$order/advance", """{"fromStatus":"DITERIMA"}""", siti)
            .conformsTo("POST", "/api/v1/orders/{id}/advance", HttpStatusCode.OK)
        api
            .post("/orders/$order/advance", """{"fromStatus":"DITERIMA"}""", siti)
            .conformsTo("POST", "/api/v1/orders/{id}/advance", HttpStatusCode.Conflict)
        api
            .post("/orders/$shift/advance", """{"fromStatus":"DITERIMA"}""", siti)
            .conformsTo("POST", "/api/v1/orders/{id}/advance", HttpStatusCode.NotFound)
        api
            .post("/orders/$order/advance", """{}""", siti)
            .conformsTo("POST", "/api/v1/orders/{id}/advance", HttpStatusCode.BadRequest)

        val capturedAt =
            Instant
                .now()
                .minusSeconds(600)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString()
        val offline =
            Tx.syncBody(
                Tx.offline(
                    capturedAt = capturedAt,
                    lines = listOf(Line(cuciSetrika, "2", 10_000)),
                    shiftId = shift,
                    newCustomer = Tx.newCustomer(Tx.key(), "Rina Kusuma", "0838-7712-4409", optIn = true),
                ),
                Tx.offline(capturedAt = capturedAt, lines = listOf(Line(cuciSetrika, "1", 11_000)), rewardId = created),
            )
        api
            .post("/orders/sync", offline, siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/orders/sync", HttpStatusCode.OK)
        api
            .post("/orders/sync", """{}""", siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/orders/sync", HttpStatusCode.BadRequest)

        api
            .post(
                "/shifts/$shift/cash-entries",
                """{"direction":"OUT","label":"beli deterjen","amount":180000}""",
                siti,
                idempotencyKey = Tx.key(),
            ).conformsTo("POST", "/api/v1/shifts/{id}/cash-entries", HttpStatusCode.Created)
        api
            .post(
                "/shifts/$shift/cash-entries",
                """{"direction":"IN","label":"","amount":1000}""",
                siti,
                idempotencyKey = Tx.key(),
            ).conformsTo("POST", "/api/v1/shifts/{id}/cash-entries", HttpStatusCode.UnprocessableEntity)
        api
            .post(
                "/shifts/$order/cash-entries",
                """{"direction":"IN","label":"x","amount":1000}""",
                siti,
                idempotencyKey = Tx.key(),
            ).conformsTo("POST", "/api/v1/shifts/{id}/cash-entries", HttpStatusCode.NotFound)
        api
            .put("/shifts/$shift/counted-cash", """{"countedCash":400000}""", siti)
            .conformsTo("PUT", "/api/v1/shifts/{id}/counted-cash", HttpStatusCode.OK)
        api
            .put("/shifts/$shift/counted-cash", """{"countedCash":-1}""", siti)
            .conformsTo("PUT", "/api/v1/shifts/{id}/counted-cash", HttpStatusCode.BadRequest)
        api
            .post("/shifts/$shift/close", """{"countedCash":400000}""", siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/shifts/{id}/close", HttpStatusCode.OK)
        api
            .post("/shifts/$shift/close", """{"countedCash":400000}""", siti, idempotencyKey = Tx.key())
            .conformsTo("POST", "/api/v1/shifts/{id}/close", HttpStatusCode.Conflict)
        api
            .put("/shifts/$shift/counted-cash", """{"countedCash":1}""", siti)
            .conformsTo("PUT", "/api/v1/shifts/{id}/counted-cash", HttpStatusCode.Conflict)

        // The shift opened by someone else's PIN hands the tablet over: `session` is a token response.
        val cipeteTablet = api.till("CPT", "3690")
        val ownerProof = proofFor(api, cipeteTablet.token, "Raka Prasetyo", "9090")
        api
            .post(
                "/shifts",
                """{"openingCash":300000,"staffProof":"$ownerProof"}""",
                cipeteTablet.token,
                idempotencyKey = Tx.key(),
            ).conformsTo("POST", "/api/v1/shifts", HttpStatusCode.Created)

        // ---- Owner report page (M3) -------------------------------------------------------
        api.get("/reports/dashboard", owner).conformsTo("GET", "/api/v1/reports/dashboard", HttpStatusCode.OK)
        api
            .get("/reports/dashboard?branchId=$tebet&periodDays=7&auditRangeDays=7", owner)
            .conformsTo("GET", "/api/v1/reports/dashboard", HttpStatusCode.OK)
        api.get("/reports/dashboard", siti).conformsTo("GET", "/api/v1/reports/dashboard", HttpStatusCode.Forbidden)
        api
            .get("/reports/dashboard?periodDays=0", owner)
            .conformsTo("GET", "/api/v1/reports/dashboard", HttpStatusCode.BadRequest)
        api
            .get("/reports/dashboard?branchId=${UUID.randomUUID()}", owner)
            .conformsTo("GET", "/api/v1/reports/dashboard", HttpStatusCode.NotFound)

        val cursor =
            api
                .get("/sync/bootstrap", siti)
                .conformsTo("GET", "/api/v1/sync/bootstrap", HttpStatusCode.OK)
                .json()
                .string("cursor")
        api.get("/sync/changes?since=$cursor", siti).conformsTo("GET", "/api/v1/sync/changes", HttpStatusCode.OK)
        api
            .get("/sync/changes?since=rusak", siti)
            .conformsTo("GET", "/api/v1/sync/changes", HttpStatusCode.BadRequest)
        api
            .post("/devices/heartbeat", """{"pendingCount":2,"appVersion":"1.4.0","online":true}""", siti)
            .conformsTo("POST", "/api/v1/devices/heartbeat", HttpStatusCode.OK)
        api
            .post("/devices/heartbeat", """{"online":true}""", siti)
            .conformsTo("POST", "/api/v1/devices/heartbeat", HttpStatusCode.BadRequest)
    }

    private suspend fun proofFor(
        api: TestApi,
        token: String,
        staffName: String,
        pin: String,
    ): String =
        api
            .post("/auth/verify-pin", """{"staffId":"${ApiTestSupport.staffId(staffName)}","pin":"$pin"}""", token)
            .json()
            .string("staffProof")

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
