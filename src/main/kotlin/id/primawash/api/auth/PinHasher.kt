package id.primawash.api.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PIN protection (PRD §12.2, trap T1).
 *
 * A 4–6 digit PIN is small enough to brute-force offline, so the protection is layered:
 *  - `pin_lookup = HMAC-SHA256(PIN_PEPPER, pin)` finds the candidate account without a table scan.
 *    The pepper lives in the secret manager and is **never** stored in the database, so a database
 *    dump alone cannot be used to enumerate PINs.
 *  - `pin_hash = Argon2id(pin)` verifies it, with a per-PIN random salt.
 *  - Rate limiting, per-account lockout and device binding sit on top (M1).
 *
 * A PIN is never logged, in any form, at any level.
 */
class PinHasher(
    pepper: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val pepperKey = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)

    /** Deterministic lookup key, hex encoded — the value stored in `staff.pin_lookup` (char(64)). */
    fun lookup(pin: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(pepperKey)
        return mac.doFinal(pin.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Keyed lookup for other short secrets that must not be enumerable from a database dump alone,
     * such as the 8-character device activation code. [purpose] separates the key spaces, so an
     * activation code can never collide with a PIN lookup.
     */
    fun keyedLookup(
        purpose: String,
        value: String,
    ): String = lookup("$purpose:$value")

    /** Argon2id hash in PHC string format — the value stored in `staff.pin_hash`. */
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val hash = derive(pin, salt)
        val encoder = Base64.getEncoder().withoutPadding()
        return "\$argon2id\$v=${Argon2Parameters.ARGON2_VERSION_13}" +
            "\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM" +
            "\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    /** Constant-time verification. Returns false for any malformed stored value instead of throwing. */
    fun verify(
        pin: String,
        encoded: String,
    ): Boolean {
        val parts = encoded.split("$")
        if (parts.size != PHC_PARTS || parts[1] != "argon2id") return false
        return runCatching {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(parts[PHC_SALT_INDEX])
            val expected = decoder.decode(parts[PHC_HASH_INDEX])
            MessageDigest.isEqual(derive(pin, salt, expected.size), expected)
        }.getOrDefault(false)
    }

    private fun derive(
        pin: String,
        salt: ByteArray,
        length: Int = HASH_LENGTH,
    ): ByteArray {
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        return ByteArray(length).also { generator.generateBytes(pin.toByteArray(Charsets.UTF_8), it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MEMORY_KIB = 65_536
        private const val ITERATIONS = 3
        private const val PARALLELISM = 1
        private const val SALT_LENGTH = 16
        private const val HASH_LENGTH = 32
        private const val PHC_PARTS = 6
        private const val PHC_SALT_INDEX = 4
        private const val PHC_HASH_INDEX = 5
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 6

        /** PIN space accepted by the client keypad (PRD Lampiran B: `PIN_FORMAT`). */
        fun isWellFormed(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }
    }
}
