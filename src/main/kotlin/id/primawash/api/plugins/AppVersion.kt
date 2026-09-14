package id.primawash.api.plugins

import id.primawash.api.common.AppUpdateRequiredException
import io.ktor.server.application.createRouteScopedPlugin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val APP_VERSION_HEADER = "X-App-Version"

class AppVersionConfig {
    var minimum: String = "0.0.0"
}

/**
 * `426 APP_UPDATE_REQUIRED` for clients older than `MIN_APP_VERSION` (PRD §6.1). With the default
 * minimum `0.0.0` nothing is ever refused, so a missing header only matters once a minimum is set.
 */
val AppVersionGate =
    createRouteScopedPlugin("AppVersionGate", ::AppVersionConfig) {
        val minimum = pluginConfig.minimum
        val enforced = AppVersions.compare(minimum, "0.0.0") > 0
        onCall { call ->
            if (!enforced) return@onCall
            val version = call.request.headers[APP_VERSION_HEADER]
            if (version == null || AppVersions.compare(version, minimum) < 0) {
                throw AppUpdateRequiredException(
                    "Versi aplikasi Prima Wash sudah terlalu lama — perbarui aplikasi dulu sebelum melanjutkan.",
                    buildJsonObject { put("minAppVersion", minimum) },
                )
            }
        }
    }

object AppVersions {
    /** Numeric, dot-separated comparison: "1.10.0" > "1.9.3"; suffixes such as "-beta" are ignored. */
    fun compare(
        left: String,
        right: String,
    ): Int {
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        version
            .trim()
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
