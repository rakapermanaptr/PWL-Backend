package id.primawash.api.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The one and only error shape of this API (PRD §6.2).
 *
 * `message` is an Indonesian sentence shown to the cashier verbatim, copied from PRD Lampiran B —
 * never reword it here without updating the PRD and telling the Android team.
 */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
    val requestId: String? = null,
)
