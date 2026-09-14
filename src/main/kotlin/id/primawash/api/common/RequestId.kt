package id.primawash.api.common

import java.security.SecureRandom
import java.time.Instant

/**
 * `requestId` shown in every error envelope and every log line: `req_` + a lexicographically
 * sortable ULID-style id (Crockford base32 timestamp + randomness), e.g. `req_01J9K3M2Q7XF4T8B`.
 */
object RequestId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_CHARS = 10
    private const val RANDOM_CHARS = 6
    private const val BITS = 5
    private val random = SecureRandom()

    fun next(now: Instant = Instant.now()): String {
        val builder = StringBuilder("req_")
        var millis = now.toEpochMilli()
        val time = CharArray(TIME_CHARS)
        for (index in TIME_CHARS - 1 downTo 0) {
            time[index] = ALPHABET[(millis and ((1L shl BITS) - 1)).toInt()]
            millis = millis shr BITS
        }
        builder.append(time)
        repeat(RANDOM_CHARS) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return builder.toString()
    }
}
