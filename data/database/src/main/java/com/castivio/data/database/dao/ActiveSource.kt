package com.castivio.data.database.dao

/**
 * The active subscription's id, as SQL — the same row [SourceDao.active] reads.
 *
 * ## Why every catalogue read is scoped to it
 *
 * Because the catalogue tables hold every subscription the device has ever imported,
 * and the reads used to filter by kind alone. Switching from an Xtream subscription to
 * a playlist left the channels board listing the Xtream channels under the playlist's
 * name: 9,302 rows that belonged to a subscription that was no longer active, and in
 * that case no longer saved either.
 *
 * ## Why a subquery rather than a parameter
 *
 * Room observes every table a query names, the subquery's included, so a screen reading
 * through one of these re-reads by itself when the active subscription changes — no
 * caller has to pass the id along or remember to re-query. And it is evaluated once per
 * statement against an indexed single row, so it costs a lookup and not a join.
 */
internal const val ACTIVE_SOURCE =
    "(SELECT id FROM source WHERE is_active = 1 ORDER BY created_at LIMIT 1)"
