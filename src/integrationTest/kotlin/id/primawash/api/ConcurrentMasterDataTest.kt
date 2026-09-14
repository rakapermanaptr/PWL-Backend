package id.primawash.api

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Two tablets acting at the same moment. The requests really run in parallel — both are released
 * together after their tokens exist — and the database constraint decides the winner.
 */
class ConcurrentMasterDataTest {
    @BeforeEach
    fun setUp() = ApiTestSupport.resetAndSeed()

    private suspend fun race(vararg calls: suspend () -> HttpResponse): List<HttpStatusCode> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val running = calls.map { call -> async { start.await().let { call().status } } }
                start.complete(Unit)
                running.awaitAll()
            }
        }

    @Test
    fun `should register a phone number once when two tablets submit it together`() =
        apiTest { api ->
            val siti =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Tebet 1", "TBT"),
                        "TBT",
                        "1234",
                    ).string("accessToken")
            val nia =
                api
                    .login(
                        ApiTestSupport.registerDevice("Tablet Bintaro 1", "BTR"),
                        "BTR",
                        "2468",
                    ).string("accessToken")
            val body = """{"name":"Dewi Anggraini","phone":"081233904471","optIn":true}"""

            val statuses = race({ api.post("/customers", body, siti) }, { api.post("/customers", body, nia) })

            statuses shouldContainExactlyInAnyOrder listOf(HttpStatusCode.Created, HttpStatusCode.Conflict)
            ApiTestSupport.count("SELECT count(*) FROM customers") shouldBe 1
            ApiTestSupport.count("SELECT count(*) FROM audit_log WHERE action_type = 'CUSTOMER_REGISTERED'") shouldBe 1
        }

    @Test
    fun `should give a pin to only one of two accounts created together`() =
        apiTest { api ->
            val owner = api.ownerToken()
            val tebet = ApiTestSupport.branchId("TBT")
            val statuses =
                race(
                    {
                        api.post(
                            "/staff",
                            """{"name":"Andi","pin":"8080","role":"KASIR","branchId":"$tebet"}""",
                            owner,
                        )
                    },
                    {
                        api.post(
                            "/staff",
                            """{"name":"Budi","pin":"8080","role":"KASIR","branchId":"$tebet"}""",
                            owner,
                        )
                    },
                )

            statuses shouldContainExactlyInAnyOrder listOf(HttpStatusCode.Created, HttpStatusCode.UnprocessableEntity)
            ApiTestSupport.count("SELECT count(*) FROM staff WHERE name IN ('Andi', 'Budi')") shouldBe 1
        }
}
