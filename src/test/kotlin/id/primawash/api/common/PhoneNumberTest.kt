package id.primawash.api.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhoneNumberTest {
    @Test
    fun `should normalise every written form to the 08 digits form`() {
        PhoneNumber.digits("0812-3390-4471") shouldBe "081233904471"
        PhoneNumber.digits("+62 812 3390 4471") shouldBe "081233904471"
        PhoneNumber.digits("81233904471") shouldBe "081233904471"
    }

    @Test
    fun `should validate length and prefix`() {
        PhoneNumber.isValid("0812-3390-4471") shouldBe true
        PhoneNumber.isValid("0812339") shouldBe false
        PhoneNumber.isValid("021-8290-1147") shouldBe false
    }

    @Test
    fun `should render display and E164 forms`() {
        PhoneNumber.display("081233904471") shouldBe "0812-3390-4471"
        PhoneNumber.toE164("0812-3390-4471") shouldBe "6281233904471"
    }

    @Test
    fun `should mask the middle digits for logs`() {
        PhoneNumber.mask("0812-3390-4471") shouldBe "0812-****-4471"
    }

    @Test
    fun `should validate exactly like the client and explain why a number is refused`() {
        PhoneNumber.validate("+62 812-3390-4471").getOrNull() shouldBe "081233904471"
        PhoneNumber.validate("0812 3390 4471").getOrNull() shouldBe "081233904471"
        PhoneNumber.validate("0812-339").exceptionOrNull()?.message shouldBe "Nomor HP kurang dari 10 digit"
        PhoneNumber.validate("0812-3390-44x1").exceptionOrNull()?.message shouldBe "Format nomor tidak dikenali"
        PhoneNumber.validate("021-8290-1147").exceptionOrNull()?.message shouldBe "Format nomor tidak dikenali"
        PhoneNumber.validate("0812-3390-4471-2233").exceptionOrNull()?.message shouldBe "Format nomor tidak dikenali"
    }
}
