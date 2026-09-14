package id.primawash.api.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque credentials: device tokens, refresh tokens and staff proofs.
 *
 * Each is 256 random bits with a readable prefix (`dt_`, `rt_`, `sp_`). Only the SHA-256 of a token
 * is stored — with that much entropy a plain hash is enough, unlike a 4–6 digit PIN (see PinHasher).
 * A raw token is returned to the client exactly once and never logged.
 */
class SecureTokens(
    private val random: SecureRandom = SecureRandom(),
) {
    fun newToken(prefix: String): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** A code from [alphabet], e.g. the 8-character device activation code. */
    fun newCode(
        length: Int,
        alphabet: String,
    ): String = String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })

    /** Uniform integer in `[from, until)`, for generated PINs. */
    fun nextInt(
        from: Int,
        until: Int,
    ): Int = from + random.nextInt(until - from)

    companion object {
        private const val TOKEN_BYTES = 32

        fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
