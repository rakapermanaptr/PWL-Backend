package id.primawash.api

import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource

/**
 * Every endpoint needs a contract test against `openapi.yaml` (CLAUDE.md). For M0 that surface is
 * the health pair; M1 extends this suite as each endpoint lands.
 */
class HealthContractTest {
    private val connection =
        mockk<Connection>(relaxed = true) {
            every { isValid(any()) } returns true
        }
    private val dataSource =
        mockk<DataSource> {
            every { connection } returns this@HealthContractTest.connection
        }

    @Test
    fun `should serve every path documented in openapi yaml`() =
        testApplication {
            application { apiModule(dataSource, "0.1.0-TEST") }
            val client = createClient { }

            OpenApiSpec.documentedPaths().forEach { path ->
                val response = client.get(path)
                check(response.status != HttpStatusCode.NotFound) { "$path terdokumentasi tapi tidak dilayani server" }
            }
        }

    @Test
    fun `should return exactly the fields documented for health`() =
        testApplication {
            application { apiModule(dataSource, "0.1.0-TEST") }

            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.keys shouldBe OpenApiSpec.requiredFields("HealthResponse")
            body.getValue("status").jsonPrimitive.content shouldBe "UP"
            OpenApiSpec.enumValues("HealthResponse").contains("UP") shouldBe true
        }

    @Test
    fun `should return exactly the fields documented for readiness`() =
        testApplication {
            application { apiModule(dataSource, "0.1.0-TEST") }

            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.keys shouldBe OpenApiSpec.requiredFields("ReadinessResponse")
            val documented = OpenApiSpec.enumValues("ReadinessResponse")
            documented.contains(body.getValue("status").jsonPrimitive.content) shouldBe true
            documented.contains(body.getValue("database").jsonPrimitive.content) shouldBe true
        }

    @Test
    fun `should report not ready with the documented envelope when the database is down`() =
        testApplication {
            val brokenDataSource =
                mockk<DataSource> {
                    every { connection } throws IllegalStateException("pool habis")
                }
            application { apiModule(brokenDataSource, "0.1.0-TEST") }

            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.ServiceUnavailable

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body.getValue("status").jsonPrimitive.content shouldBe "NOT_READY"
            body.getValue("database").jsonPrimitive.content shouldBe "DOWN"
        }

    @Test
    fun `should wrap an unknown route in the documented error envelope`() =
        testApplication {
            application { apiModule(dataSource, "0.1.0-TEST") }

            val response = client.get("/api/v1/tidak-ada")
            response.status shouldBe HttpStatusCode.NotFound

            val error =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            error.getValue("code").jsonPrimitive.content shouldBe "NOT_FOUND"
            error.containsKey("message") shouldBe true
            error
                .getValue("requestId")
                .jsonPrimitive.content
                .startsWith("req_") shouldBe true
        }
}
