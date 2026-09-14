package id.primawash.api.auth

import id.primawash.api.common.UnauthenticatedException
import id.primawash.api.support.NOW
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class AccessTokensTest {
    private val claims =
        AccessClaims(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "KASIR")

    @Test
    fun `should read back the claims it signed`() {
        val tokens = AccessTokens("secret-untuk-test", Clock.fixed(NOW, ZoneOffset.UTC))
        tokens.verify(tokens.issue(claims)) shouldBe claims
    }

    @Test
    fun `should tell an expired token from a forged one`() {
        val issued = AccessTokens("secret-untuk-test", Clock.fixed(NOW, ZoneOffset.UTC)).issue(claims)

        val later =
            AccessTokens("secret-untuk-test", Clock.fixed(NOW.plus(AccessTokens.TTL).plusSeconds(1), ZoneOffset.UTC))
        assertThrows<UnauthenticatedException> { later.verify(issued) }.code shouldBe "TOKEN_EXPIRED"

        val otherSecret = AccessTokens("secret-lain", Clock.fixed(NOW, ZoneOffset.UTC))
        assertThrows<UnauthenticatedException> { otherSecret.verify(issued) }.code shouldBe "UNAUTHENTICATED"
        assertThrows<UnauthenticatedException> { otherSecret.verify("bukan.token.jwt") }.code shouldBe "UNAUTHENTICATED"
    }
}
