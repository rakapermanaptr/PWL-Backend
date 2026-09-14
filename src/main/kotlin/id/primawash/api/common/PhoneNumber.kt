package id.primawash.api.common

/**
 * Phone numbers are stored twice: `phone` for display ("0812-3390-4471") and `phone_digits`
 * (unique, "08…", 10–13 digits). WhatsApp needs E.164 without `+`: "6281233904471".
 * Logs only ever show the masked form (CLAUDE.md "Security Rules").
 */
object PhoneNumber {
    private const val MIN_DIGITS = 10
    private const val MAX_DIGITS = 13
    private const val MASK_PREFIX = 4
    private const val MASK_SUFFIX = 4
    private const val GROUP_SIZE = 4
    private const val SECOND_GROUP_START = 4
    private const val THIRD_GROUP_START = 8

    /** Strips every non-digit and normalises `+62…`/`62…` to the local `08…` form. */
    fun digits(raw: String): String {
        val onlyDigits = raw.filter { it.isDigit() }
        return when {
            onlyDigits.startsWith("62") -> "0" + onlyDigits.removePrefix("62")
            onlyDigits.startsWith("8") -> "0$onlyDigits"
            else -> onlyDigits
        }
    }

    fun isValid(raw: String): Boolean {
        val digits = digits(raw)
        return digits.startsWith("08") && digits.length in MIN_DIGITS..MAX_DIGITS
    }

    /** "0812-3390-4471" — the display format used by the client and by WhatsApp message bodies. */
    fun display(raw: String): String {
        val digits = digits(raw)
        if (digits.length < MIN_DIGITS) return raw
        return listOf(
            digits.take(GROUP_SIZE),
            digits.drop(SECOND_GROUP_START).take(GROUP_SIZE),
            digits.drop(THIRD_GROUP_START),
        ).filter { it.isNotEmpty() }
            .joinToString("-")
    }

    /** E.164 without `+`, the only shape the WhatsApp Cloud API accepts. */
    fun toE164(raw: String): String = "62" + digits(raw).removePrefix("0")

    /** "0812-****-4471" — the only form a phone number may take in a log line. */
    fun mask(raw: String): String {
        val digits = digits(raw)
        if (digits.length <= MASK_PREFIX + MASK_SUFFIX) return "*".repeat(digits.length)
        return digits.take(MASK_PREFIX) + "-****-" + digits.takeLast(MASK_SUFFIX)
    }
}
