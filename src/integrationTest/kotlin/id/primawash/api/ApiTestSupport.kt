package id.primawash.api

import id.primawash.api.auth.PinHasher
import id.primawash.api.common.SecureTokens
import id.primawash.api.db.DatabaseFactory
import id.primawash.api.plugins.ApiSettings
import id.primawash.api.tools.seedPilot
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock the test moves by hand — lockouts and expiries are asserted without sleeping. */
class MutableClock(
    var now: Instant = Instant.parse("2026-09-15T02:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(duration: java.time.Duration) {
        now = now.plus(duration)
    }
}

/**
 * Boots the real API — plugins, Koin, routes — against the Testcontainers Postgres, seeded with the
 * pilot master data of PRD Lampiran D (demo PINs: Siti 1234, Bagas 5678, Nia 2468, Raka 9090, …).
 */
object ApiTestSupport {
    const val PEPPER = "pepper-untuk-test"
    val hasher = PinHasher(PEPPER)
    val settings =
        ApiSettings(
            jwtSecret = "jwt-secret-untuk-test-0000000000000000000000000000",
            pinPepper = PEPPER,
            minAppVersion = "0.0.0",
            appVersion = "0.1.0-TEST",
        )

    val database: Database by lazy { DatabaseFactory.connect(PostgresSupport.dataSource) }

    fun resetAndSeed() {
        PostgresSupport.truncateAll()
        PostgresSupport.withConnection { connection ->
            connection.autoCommit = false
            seedPilot(connection, hasher)
            connection.commit()
        }
    }

    fun uuidOf(sql: String): UUID =
        PostgresSupport.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use {
                    check(it.next()) { "Tidak ada baris untuk: $sql" }
                    it.getObject(1) as UUID
                }
            }
        }

    fun branchId(code: String): UUID = uuidOf("SELECT id FROM branches WHERE code = '$code'")

    fun staffId(name: String): UUID = uuidOf("SELECT id FROM staff WHERE name = '$name'")

    fun count(sql: String): Long =
        PostgresSupport.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** Audit rows with exactly this Indonesian sentence — the wording is part of the contract. */
    fun auditCount(action: String): Long =
        PostgresSupport.withConnection { connection ->
            connection.prepareStatement("SELECT count(*) FROM audit_log WHERE action = ?").use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** Registers a tablet directly in the database and returns its raw device token. */
    fun registerDevice(
        name: String,
        branchCode: String? = null,
    ): String {
        val token = SecureTokens().newToken("dt_")
        val branch = branchCode?.let { "'${branchId(it)}'" } ?: "NULL"
        PostgresSupport.withConnection { connection ->
            connection.createStatement().use {
                it.execute(
                    "INSERT INTO devices (name, last_branch_id, token_hash) " +
                        "VALUES ('$name', $branch, '${SecureTokens.sha256Hex(token)}')",
                )
            }
        }
        return token
    }
}

fun apiTest(
    clock: Clock = Clock.systemUTC(),
    settings: ApiSettings = ApiTestSupport.settings,
    block: suspend ApplicationTestBuilder.(TestApi) -> Unit,
) = testApplication {
    application {
        apiModule(PostgresSupport.dataSource, ApiTestSupport.database, settings, clock)
    }
    block(TestApi(client))
}

/** Thin JSON helpers over the test client; every body is parsed, never deserialised into server DTOs. */
class TestApi(
    val client: HttpClient,
) {
    suspend fun get(
        path: String,
        token: String? = null,
    ): HttpResponse = client.get(API + path) { auth(token) }

    suspend fun post(
        path: String,
        body: String? = null,
        token: String? = null,
    ): HttpResponse =
        client.post(API + path) {
            auth(token)
            json(body)
        }

    suspend fun patch(
        path: String,
        body: String,
        token: String? = null,
    ): HttpResponse =
        client.patch(API + path) {
            auth(token)
            json(body)
        }

    suspend fun put(
        path: String,
        body: String,
        token: String? = null,
    ): HttpResponse =
        client.put(API + path) {
            auth(token)
            json(body)
        }

    suspend fun delete(
        path: String,
        token: String? = null,
    ): HttpResponse = client.delete(API + path) { auth(token) }

    /** PIN login on [deviceToken] at [branchCode]; returns the parsed token response. */
    suspend fun login(
        deviceToken: String,
        branchCode: String,
        pin: String,
    ): JsonObject {
        val response =
            post(
                "/auth/pin-login",
                """{"branchId":"${ApiTestSupport.branchId(branchCode)}","pin":"$pin"}""",
                deviceToken,
            )
        response.status shouldBe HttpStatusCode.OK
        return response.json()
    }

    suspend fun ownerToken(deviceToken: String = ApiTestSupport.registerDevice("Tablet Owner", "TBT")): String =
        login(deviceToken, "TBT", "9090").string("accessToken")

    private fun HttpRequestBuilder.auth(token: String?) {
        token?.let { bearerAuth(it) }
    }

    private fun HttpRequestBuilder.json(body: String?) {
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    companion object {
        const val API = "/api/v1"
    }
}

suspend fun HttpResponse.json(): JsonObject =
    kotlinx.serialization.json.Json
        .parseToJsonElement(bodyAsText())
        .jsonObject

fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

fun JsonElement.errorCode(): String =
    jsonObject
        .getValue("error")
        .jsonObject
        .getValue("code")
        .jsonPrimitive.content

suspend fun HttpResponse.shouldFailWith(
    status: HttpStatusCode,
    code: String,
): JsonObject {
    val body = json()
    check(this.status == status && body.errorCode() == code) { "Diharapkan $status $code, dapat ${this.status}: $body" }
    return body.obj("error")
}
