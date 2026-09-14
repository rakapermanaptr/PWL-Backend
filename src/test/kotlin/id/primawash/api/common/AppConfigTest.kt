package id.primawash.api.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppConfigTest {
    private val stagingSecrets =
        mapOf(
            "APP_ENV" to "staging",
            "JWT_SECRET" to "test-jwt-secret",
            "PIN_PEPPER" to "test-pin-pepper",
        )

    @Test
    fun `should migrate on boot in dev by default`() {
        AppConfig.fromEnvironment { null }.runMigrationsOnBoot shouldBe true
    }

    @Test
    fun `should not migrate on boot in staging by default`() {
        AppConfig.fromEnvironment(stagingSecrets::get).runMigrationsOnBoot shouldBe false
    }

    @Test
    fun `should let RUN_MIGRATIONS_ON_BOOT override the environment default`() {
        val env = stagingSecrets + ("RUN_MIGRATIONS_ON_BOOT" to "true")
        AppConfig.fromEnvironment(env::get).runMigrationsOnBoot shouldBe true
    }

    @Test
    fun `should refuse to start in staging without secrets`() {
        shouldThrow<IllegalStateException> {
            AppConfig.fromEnvironment(mapOf("APP_ENV" to "staging")::get)
        }
    }

    @Test
    fun `should read database config without requiring app secrets`() {
        val env =
            mapOf(
                "APP_ENV" to "staging",
                "DATABASE_URL" to "jdbc:postgresql://db:25060/pwl?sslmode=require",
                "DATABASE_USER" to "pwl_migrator",
            )
        val database = DatabaseConfig.fromEnvironment(env::get)
        database.url shouldBe "jdbc:postgresql://db:25060/pwl?sslmode=require"
        database.user shouldBe "pwl_migrator"
    }
}
