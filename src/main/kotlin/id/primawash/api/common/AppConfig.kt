package id.primawash.api.common

/** Deployment environment. Some behaviour (seeding, secret requirements) depends on it. */
enum class AppEnvironment {
    DEV,
    STAGING,
    PROD,
    ;

    val isProduction: Boolean get() = this == PROD
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

/**
 * Runtime configuration, read from the environment.
 *
 * Note what is deliberately absent: `WA_*`. The Meta/WhatsApp integration is the final milestone
 * (see README "Integrasi Meta / WhatsApp"), and the API must boot and pass its tests without any
 * WhatsApp credential. The worker process will read those variables itself when it is built.
 */
data class AppConfig(
    val environment: AppEnvironment,
    val database: DatabaseConfig,
    val jwtSecret: String,
    val pinPepper: String,
    val minAppVersion: String,
) {
    companion object {
        private const val DEV_JWT_SECRET = "dev-only-jwt-secret-change-me-0000000000000000"
        private const val DEV_PIN_PEPPER = "dev-only-pin-pepper-change-me-0000000000000000"
        private const val DEFAULT_POOL_SIZE = 10

        fun fromEnvironment(env: (String) -> String? = { System.getenv(it) }): AppConfig {
            val environment =
                when (val raw = env("APP_ENV")?.uppercase()) {
                    null, "DEV", "LOCAL" -> AppEnvironment.DEV
                    "STAGING" -> AppEnvironment.STAGING
                    "PROD", "PRODUCTION" -> AppEnvironment.PROD
                    else -> error("APP_ENV tidak dikenal: $raw")
                }
            return AppConfig(
                environment = environment,
                database =
                    DatabaseConfig(
                        url = env("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/pwl",
                        user = env("DATABASE_USER") ?: "pwl",
                        password = env("DATABASE_PASSWORD") ?: "pwl",
                        maxPoolSize = env("DATABASE_POOL_SIZE")?.toInt() ?: DEFAULT_POOL_SIZE,
                    ),
                jwtSecret = required(env, "JWT_SECRET", environment, DEV_JWT_SECRET),
                pinPepper = required(env, "PIN_PEPPER", environment, DEV_PIN_PEPPER),
                minAppVersion = env("MIN_APP_VERSION") ?: "0.0.0",
            )
        }

        private fun required(
            env: (String) -> String?,
            name: String,
            environment: AppEnvironment,
            devFallback: String,
        ): String {
            val value = env(name)
            if (!value.isNullOrBlank()) return value
            check(environment == AppEnvironment.DEV) {
                "$name wajib diisi di environment $environment — ambil dari secret manager."
            }
            return devFallback
        }
    }
}
