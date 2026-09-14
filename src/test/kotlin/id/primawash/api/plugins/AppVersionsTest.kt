package id.primawash.api.plugins

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppVersionsTest {
    @Test
    fun `should compare versions numerically, not as text`() {
        (AppVersions.compare("1.10.0", "1.9.3") > 0) shouldBe true
        (AppVersions.compare("1.4", "1.4.0") == 0) shouldBe true
        (AppVersions.compare("1.4.0-beta", "1.4.0") == 0) shouldBe true
        (AppVersions.compare("0.9.9", "1.0.0") < 0) shouldBe true
    }
}
