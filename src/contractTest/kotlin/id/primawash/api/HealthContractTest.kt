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
import org.junit.jupiter.api.assertThrows
import javax.sql.DataSource

/** The unauthenticated surface: health, readiness, and the envelope of an unknown route. */
class HealthContractTest {
    @Test
    fun `should match the documented health and readiness responses`() =
        apiTest { api ->
            val health = api.client.get("/health")
            OpenApiContract.validate(Operation("GET", "/health"), health.status.value, health.bodyAsText())

            val ready = api.client.get("/health/ready")
            ready.status shouldBe HttpStatusCode.OK
            OpenApiContract.validate(Operation("GET", "/health/ready"), ready.status.value, ready.bodyAsText())
        }

    @Test
    fun `should reject an undocumented field, a missing field and a wrong type`() {
        val health = Operation("GET", "/health")
        OpenApiContract.validate(health, 200, """{"status":"UP","version":"1"}""")

        assertThrows<IllegalStateException> {
            OpenApiContract.validate(health, 200, """{"status":"UP","version":"1","extra":true}""")
        }
        assertThrows<IllegalStateException> { OpenApiContract.validate(health, 200, """{"status":"UP"}""") }
        assertThrows<IllegalStateException> {
            OpenApiContract.validate(Operation("GET", "/api/v1/me"), 200, """{"staff":{},"branch":{}}""")
        }
        assertThrows<IllegalStateException> {
            OpenApiContract.validate(Operation("GET", "/api/v1/branches"), 200, """{"items":[{"id":"x"}]}""")
        }
    }

    @Test
    fun `should report not ready with the documented body when the database is down`() =
        testApplication {
            val broken = mockk<DataSource> { every { connection } throws IllegalStateException("pool habis") }
            application { apiModule(broken, ApiTestSupport.database, ApiTestSupport.settings) }

            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            OpenApiContract.validate(Operation("GET", "/health/ready"), 503, response.bodyAsText())
        }

    @Test
    fun `should wrap an unknown route in the documented error envelope`() =
        apiTest { api ->
            val response = api.client.get("/api/v1/tidak-ada")
            response.status shouldBe HttpStatusCode.NotFound

            val error =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonObject
            error.getValue("code").jsonPrimitive.content shouldBe "NOT_FOUND"
            error
                .getValue("requestId")
                .jsonPrimitive.content
                .startsWith("req_") shouldBe true
        }
}
