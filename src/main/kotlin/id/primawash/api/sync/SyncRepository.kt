package id.primawash.api.sync

import id.primawash.api.db.RawSql
import java.util.UUID

/** One changed row in the change feed, ordered by `(xid, collection, id)`. */
data class ChangeKey(
    /** `change_xid` as its decimal text — compared by Postgres as `xid8`, never by the JVM. */
    val xid: String,
    val collection: SyncCollection,
    val id: UUID,
)

/** Collections a tablet caches (PRD §8.10); the name is the JSON key and the feed's sort key. */
enum class SyncCollection(
    val key: String,
) {
    BRANCHES("branches"),
    CUSTOMERS("customers"),
    LOYALTY_RATES("loyaltyRates"),
    ORDERS("orders"),
    REWARDS("rewards"),
    SERVICES("services"),
    SHIFTS("shifts"),
    STAFF("staff"),
    ;

    companion object {
        fun ofKey(key: String): SyncCollection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The change feed behind `GET /sync/changes` — a watermark on the id of the transaction that last wrote
 * each row (`change_xid`, V5). See the V5 migration for why this and not `updated_at`.
 */
class SyncRepository {
    /**
     * The oldest transaction still running when this is read. Every write not yet visible now carries a
     * `change_xid` at or above it, so reading `change_xid >= watermark` next time cannot miss it.
     */
    fun watermark(): String = RawSql.single("SELECT pg_snapshot_xmin(pg_current_snapshot())::text") { it.getString(1) }

    /**
     * Keys of rows changed at or after [since], after the key [after] when continuing a page, in feed
     * order. [staffAndShiftBranch] narrows staff (its cashiers plus owners) and shifts to one branch — null
     * for an owner, who sees every branch. Orders always follow [orderBranch], the tablet's branch.
     */
    fun changedKeys(
        since: String,
        after: ChangeKey?,
        orderBranch: UUID,
        staffAndShiftBranch: UUID?,
        limit: Int,
    ): List<ChangeKey> =
        RawSql.query(
            CHANGE_FEED,
            since,
            since,
            staffAndShiftBranch,
            staffAndShiftBranch,
            since,
            since,
            since,
            since,
            since,
            orderBranch,
            since,
            staffAndShiftBranch,
            staffAndShiftBranch,
            after?.xid,
            after?.xid,
            after?.collection?.key,
            after?.id,
            limit,
        ) { row ->
            ChangeKey(
                xid = row.getString("xid"),
                collection = requireNotNull(SyncCollection.ofKey(row.getString("collection"))),
                id = row.getObject("id") as UUID,
            )
        }

    private companion object {
        val CHANGE_FEED =
            """
            SELECT c.xid::text AS xid, c.collection, c.id FROM (
                SELECT change_xid AS xid, 'branches'::text AS collection, id FROM branches
                    WHERE change_xid >= ?::xid8
                UNION ALL
                SELECT change_xid, 'staff'::text, id FROM staff
                    WHERE change_xid >= ?::xid8
                      AND (?::uuid IS NULL OR branch_id = ?::uuid OR branch_id IS NULL)
                UNION ALL
                SELECT change_xid, 'services'::text, id FROM services WHERE change_xid >= ?::xid8
                UNION ALL
                SELECT change_xid, 'rewards'::text, id FROM rewards WHERE change_xid >= ?::xid8
                UNION ALL
                SELECT change_xid, 'loyaltyRates'::text, id FROM loyalty_rates WHERE change_xid >= ?::xid8
                UNION ALL
                SELECT change_xid, 'customers'::text, id FROM customers WHERE change_xid >= ?::xid8
                UNION ALL
                SELECT change_xid, 'orders'::text, id FROM orders
                    WHERE change_xid >= ?::xid8 AND branch_id = ?::uuid
                UNION ALL
                SELECT change_xid, 'shifts'::text, id FROM shifts
                    WHERE change_xid >= ?::xid8 AND (?::uuid IS NULL OR branch_id = ?::uuid)
            ) c
            WHERE (?::xid8 IS NULL OR (c.xid, c.collection, c.id) > (?::xid8, ?::text, ?::uuid))
            ORDER BY c.xid, c.collection, c.id
            LIMIT ?
            """.trimIndent()
    }
}
