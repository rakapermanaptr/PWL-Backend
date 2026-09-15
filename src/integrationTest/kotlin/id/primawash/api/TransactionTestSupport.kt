@file:Suppress("TooManyFunctions")

package id.primawash.api

import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/** One tablet with a staff session on it. */
data class Till(
    val token: String,
    val deviceId: String,
)

/** One cart line as the tablet sends it; [qty] is the JSON number text ("4.5"). */
data class Line(
    val serviceId: String,
    val qty: String,
    val unitPrice: Long,
)

/** Helpers for the M2 flows: shifts, orders and the offline queue, all through the real API. */
object Tx {
    fun serviceId(name: String): String =
        ApiTestSupport.uuidOf("SELECT id FROM services WHERE name = '$name'").toString()

    fun rewardId(name: String): String = ApiTestSupport.uuidOf("SELECT id FROM rewards WHERE name = '$name'").toString()

    fun key(): String = UUID.randomUUID().toString()

    /** Gives a customer an opening balance the way an import does: a ledger row plus the cached balance. */
    fun givePoints(
        customerId: String,
        points: Long,
    ) = PostgresSupport.withConnection { connection ->
        connection.createStatement().use {
            it.execute(
                "INSERT INTO points_ledger (customer_id, type, delta, balance_after) " +
                    "VALUES ('$customerId', 'IMPORT', $points, $points); " +
                    "UPDATE customers SET points_balance = $points WHERE id = '$customerId'",
            )
        }
    }

    fun orderBody(
        clientTxId: String,
        lines: List<Line>,
        expectedTotal: Long,
        customerId: String? = null,
        rewardId: String? = null,
        payment: String = "TUNAI",
        note: String? = null,
    ): String =
        buildJsonObject {
            put("clientTxId", clientTxId)
            put("customerId", customerId)
            putJsonArray("items") {
                lines.forEach { line ->
                    addJsonObject {
                        put("serviceId", line.serviceId)
                        put("qty", JsonPrimitive(line.qty.toBigDecimal()))
                        put("unitPrice", line.unitPrice)
                    }
                }
            }
            put("rewardId", rewardId)
            put("payment", payment)
            put("note", note)
            put("expectedTotal", expectedTotal)
        }.toString()

    @Suppress("LongParameterList")
    fun offline(
        clientTxId: String = key(),
        capturedAt: String,
        lines: List<Line>,
        shiftId: String? = null,
        staffId: String? = null,
        customerId: String? = null,
        newCustomer: JsonObject? = null,
        payment: String = "TUNAI",
        rewardId: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("clientTxId", clientTxId)
            put("capturedAt", capturedAt)
            put("shiftId", shiftId)
            put("staffId", staffId)
            put("customerId", customerId)
            newCustomer?.let { put("newCustomer", it) }
            putJsonArray("items") {
                lines.forEach { line ->
                    addJsonObject {
                        put("serviceId", line.serviceId)
                        put("qty", JsonPrimitive(line.qty.toBigDecimal()))
                        put("unitPrice", line.unitPrice)
                    }
                }
            }
            put("rewardId", rewardId)
            put("payment", payment)
            put("note", "")
        }

    fun syncBody(vararg transactions: JsonObject): String = syncBody(transactions.toList())

    fun syncBody(transactions: List<JsonObject>): String =
        buildJsonObject { put("transactions", JsonArray(transactions)) }.toString()

    fun newCustomer(
        id: String,
        name: String,
        phone: String,
        optIn: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("phone", phone)
            put("optIn", optIn)
        }

    /** Releases every call at the same moment on IO threads, so the database — not the test — picks the winner. */
    suspend fun race(vararg calls: suspend () -> HttpResponse): List<HttpResponse> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val running = calls.map { call -> async { start.await().let { call() } } }
                start.complete(Unit)
                running.awaitAll()
            }
        }
}

suspend fun TestApi.till(
    branchCode: String,
    pin: String,
): Till {
    val device = ApiTestSupport.newDevice()
    return Till(login(device, branchCode, pin).string("accessToken"), device)
}

suspend fun TestApi.staffProof(
    till: Till,
    staffName: String,
    pin: String,
): String {
    val response =
        post("/auth/verify-pin", """{"staffId":"${ApiTestSupport.staffId(staffName)}","pin":"$pin"}""", till.token)
    response.status shouldBe HttpStatusCode.OK
    return response.json().string("staffProof")
}

/** Opens the shift of the till's branch as [staffName] — the staff member already logged in on it. */
suspend fun TestApi.openShift(
    till: Till,
    staffName: String,
    pin: String,
    openingCash: Long = 500_000,
): JsonObject {
    val proof = staffProof(till, staffName, pin)
    val body = """{"openingCash":$openingCash,"staffProof":"$proof"}"""
    val response = post("/shifts", body, till.token, idempotencyKey = Tx.key())
    response.status shouldBe HttpStatusCode.Created
    return response.json().obj("shift")
}

suspend fun TestApi.registerCustomer(
    till: Till,
    name: String,
    phone: String,
    optIn: Boolean,
): String {
    val response = post("/customers", """{"name":"$name","phone":"$phone","optIn":$optIn}""", till.token)
    response.status shouldBe HttpStatusCode.Created
    return response.json().obj("customer").string("id")
}

suspend fun TestApi.placeOrder(
    till: Till,
    body: String,
    key: String = Tx.key(),
): HttpResponse = post("/orders", body, till.token, idempotencyKey = key)

/** A walk-in cash order for [lines]; returns the order object. */
suspend fun TestApi.walkIn(
    till: Till,
    vararg lines: Line,
    payment: String = "TUNAI",
): JsonObject {
    val total = lines.sumOf { (it.qty.toBigDecimal() * it.unitPrice.toBigDecimal()).toLong() }
    val response = placeOrder(till, Tx.orderBody(Tx.key(), lines.toList(), total, payment = payment))
    response.status shouldBe HttpStatusCode.Created
    return response.json().obj("order")
}

suspend fun TestApi.advance(
    till: Till,
    orderId: String,
    fromStatus: String,
): HttpResponse = post("/orders/$orderId/advance", """{"fromStatus":"$fromStatus"}""", till.token)

suspend fun TestApi.sync(
    till: Till,
    body: String,
    key: String = Tx.key(),
): HttpResponse = post("/orders/sync", body, till.token, idempotencyKey = key)

fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

fun JsonArray.objects(): List<JsonObject> = map { it.jsonObject }

fun JsonObject.isNull(key: String): Boolean = this[key] == null || this[key].toString() == "null"

/** Recorded audit sentences starting with [prefix], oldest first. */
fun auditActions(prefix: String): List<String> =
    PostgresSupport.withConnection { connection ->
        connection.prepareStatement("SELECT action FROM audit_log WHERE action LIKE ? ORDER BY id").use { statement ->
            statement.setString(1, "$prefix%")
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

/** Single string value of [sql]. */
fun scalar(sql: String): String? =
    PostgresSupport.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
        }
    }
