package id.primawash.api.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class PinHasherTest {
    private val hasher = PinHasher("pepper-untuk-test")

    @Test
    fun `should verify the pin it hashed`() {
        val encoded = hasher.hash("1234")
        hasher.verify("1234", encoded) shouldBe true
        hasher.verify("4321", encoded) shouldBe false
    }

    @Test
    fun `should salt every hash so two staff with the same pin differ`() {
        hasher.hash("1234") shouldNotBe hasher.hash("1234")
    }

    @Test
    fun `should never embed the pin in the stored value`() {
        hasher.hash("5678") shouldNotContain "5678"
    }

    @Test
    fun `should produce a stable lookup key that depends on the pepper`() {
        val other = PinHasher("pepper-lain")
        hasher.lookup("1234") shouldBe hasher.lookup("1234")
        hasher.lookup("1234").length shouldBe 64
        hasher.lookup("1234") shouldNotBe other.lookup("1234")
    }

    @Test
    fun `should reject a malformed stored hash instead of throwing`() {
        hasher.verify("1234", "bukan-hash") shouldBe false
        hasher.verify("1234", "\$argon2id\$v=19\$rusak") shouldBe false
    }

    @Test
    fun `should accept only 4 to 6 digit pins`() {
        PinHasher.isWellFormed("1234") shouldBe true
        PinHasher.isWellFormed("123456") shouldBe true
        PinHasher.isWellFormed("123") shouldBe false
        PinHasher.isWellFormed("1234567") shouldBe false
        PinHasher.isWellFormed("12a4") shouldBe false
    }
}
