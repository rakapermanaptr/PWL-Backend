package id.primawash.api.sync

import id.primawash.api.common.Pagination
import id.primawash.api.common.ValidationException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class SyncCursorTest {
    @Test
    fun `should round-trip a complete cursor and a continuation`() {
        val complete = SyncCursor.complete("7412")
        SyncCursor.decode(complete.encode()) shouldBe complete

        val continuation = SyncCursor("7412", "7520", ChangeKey("7431", SyncCollection.ORDERS, UUID.randomUUID()))
        SyncCursor.decode(continuation.encode()) shouldBe continuation
    }

    @Test
    fun `should refuse anything the server did not issue`() {
        val forged =
            listOf(
                "bukan-cursor",
                Pagination.encode("v0|7412"),
                Pagination.encode("v1|7412; DROP TABLE orders"),
                Pagination.encode("v1|-1"),
                Pagination.encode("v1|7412|7520|7431|pesanan|${UUID.randomUUID()}"),
                Pagination.encode("v1|7412|7520|7431|orders|bukan-uuid"),
                Pagination.encode("v1|7412|7520|7431|orders"),
            )

        forged.forEach { raw ->
            assertThrows<ValidationException> { SyncCursor.decode(raw) }.message shouldBe
                "Cursor tidak valid — muat ulang data dari awal (bootstrap)."
        }
    }
}
