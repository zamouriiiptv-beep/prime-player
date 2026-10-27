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

/**
 * The rows a viewer has not hidden, as an SQL predicate -- for a query over `media` read by its own
 * name. A row is hidden when its id is in `hidden_item`, or the id of the bouquet it is in is: hiding a
 * bouquet hides its channels with it, without a row for each of them.
 *
 * Added to the channel lists' windows and ranks and to the counts of a search -- queries that visit a
 * window of rows or the few a search found -- so a hidden channel is in none of them: not in the list, not
 * in a search, and not stepped to by the channel keys. Nothing is deleted -- the catalogue keeps every
 * row, and restoring is deleting the viewer's row from `hidden_item`. Two primary-key lookups a row,
 * against a table of the handful of things a viewer chose to hide; and Room observes the table through
 * the subquery, so a list reading through this re-reads by itself when something is hidden.
 *
 * **Not for a count of a whole section or a bouquet.** That is a visit to every row, and the sections hold
 * tens of thousands: those subtract what is hidden instead -- see [HIDDEN_IN_KIND].
 */
internal const val NOT_HIDDEN =
    "NOT EXISTS (SELECT 1 FROM hidden_item WHERE hidden_item.item_id IN (media.id, media.group_id))"

/** [NOT_HIDDEN] for a query that reads `media` as `m`. */
internal const val NOT_HIDDEN_M =
    "NOT EXISTS (SELECT 1 FROM hidden_item WHERE hidden_item.item_id IN (m.id, m.group_id))"

/**
 * How many rows of `:kind` the viewer has hidden in the active subscription, **counted from the hidden
 * items and not from the catalogue** -- so a count of what is left is the section's indexed `COUNT` less
 * this, and no row of a 55,000-channel section is visited to ask whether it is hidden.
 *
 * Two terms, which do not overlap: every row of the bouquets that are hidden -- an index range on
 * `source_id, kind, group_id` per hidden bouquet, read from the index alone -- and the channels hidden by
 * themselves, one primary-key lookup each, leaving out those inside a bouquet that is hidden already,
 * which the first term has counted. Both are as long as what the viewer hid and not as long as the
 * catalogue; with nothing hidden both are empty. Parenthesised as a whole, so it is subtracted whole.
 */
internal const val HIDDEN_IN_KIND =
    "((SELECT COUNT(*) FROM media m WHERE m.source_id = $ACTIVE_SOURCE AND m.kind = :kind " +
        "AND m.group_id IN (SELECT item_id FROM hidden_item WHERE type = 'BOUQUET')) " +
        "+ (SELECT COUNT(*) FROM hidden_item h INNER JOIN media m ON m.id = h.item_id " +
        "WHERE h.type = 'CHANNEL' AND m.source_id = $ACTIVE_SOURCE AND m.kind = :kind " +
        "AND (m.group_id IS NULL OR m.group_id NOT IN (SELECT item_id FROM hidden_item WHERE type = 'BOUQUET'))))"

/** The channels of `:groupId` the viewer hid one by one, counted from the hidden items -- see [HIDDEN_IN_KIND]. */
internal const val HIDDEN_IN_GROUP =
    "(SELECT COUNT(*) FROM hidden_item h INNER JOIN media m ON m.id = h.item_id " +
        "WHERE h.type = 'CHANNEL' AND m.source_id = $ACTIVE_SOURCE AND m.kind = :kind AND m.group_id = :groupId)"

/**
 * What a show is sorted by, in a query that groups `media` by `series_id`: the sort title of the show's own row --
 * the shell an Xtream import writes with no episode number, with the provider's decoration taken off -- and, for a
 * show that has none (a playlist's, made of episodes alone), its name in lower case. Never an episode's own sort title:
 * a bare `min(sort_title)` ordered a show by whichever of its episodes came first in the alphabet.
 */
internal const val SHOW_SORT_TITLE =
    "COALESCE(min(CASE WHEN episode_number IS NULL THEN sort_title END), lower(series_title))"
