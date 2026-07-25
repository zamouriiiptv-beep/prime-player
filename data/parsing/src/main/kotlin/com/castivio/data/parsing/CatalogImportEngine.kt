package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind

/**
 * Turns a playlist into catalogue rows without ever holding the catalogue.
 *
 * The whole pipeline is one pass: [M3uParser] hands over entries one at a time,
 * each is classified, and rows accumulate in a single reused batch that is
 * flushed to the [CatalogWriter] and committed every [batchSize] rows. Peak
 * memory is *the batch*, not the playlist — a 400,000 entry import and a 400
 * entry import have the same footprint.
 *
 * The only structure that grows is the group index, which is bounded by the
 * provider's category count (hundreds), not by item count.
 *
 * Committing per batch is what makes a large import usable: the UI observes the
 * tables, so the first channels appear in well under a second while the rest is
 * still arriving. A single transaction around the whole file would be marginally
 * faster to complete and show nothing for twenty seconds.
 *
 * Blocking by design — SQLite is blocking. Call it on an IO dispatcher.
 */
class CatalogImportEngine(
    private val writer: CatalogWriter,
    private val batchSize: Int = DEFAULT_BATCH,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(batchSize in 1..MAX_BATCH) { "batchSize $batchSize outside 1..$MAX_BATCH" }
    }

    /**
     * @param sourceId identifies the provider this catalogue belongs to; it is
     *   part of every generated id, so two sources never collide.
     * @param lines the playlist, read lazily. A pre-read `List` defeats the
     *   entire point of this class.
     * @param onProgress called on the calling thread after each committed
     *   batch, then once with [ImportProgress.Done].
     * @param isCancelled checked between batches. Cheap to poll and precise
     *   enough: a batch is milliseconds of work.
     */
    fun importM3u(
        sourceId: String,
        lines: Sequence<String>,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportSummary {
        val started = clock()

        val batch = ArrayList<CatalogItem>(batchSize)
        // Bounded by the provider's category count, so this is the one thing the
        // importer is allowed to keep.
        val groups = LinkedHashMap<String, MediaGroup>()
        val newGroups = ArrayList<MediaGroup>()
        val perKind = IntArray(MediaKind.entries.size)

        var imported = 0
        var order = 0
        var cancelled = false

        // Cancellation stops the *source*, not just the callback. Draining a
        // 100 MB download to reach the end of a cancelled import would keep the
        // network and CPU busy for a screen the user has already left.
        val guarded = lines.takeWhile { !cancelled }

        writer.begin(sourceId)
        try {
            val stats = M3uParser.parse(guarded) { entry ->
                val classification = MediaClassifier.classify(entry)
                val kind = classification.kind
                perKind[kind.ordinal]++

                val group = entry.groupTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    // Keyed by kind as well as name: providers reuse "4K" for
                    // both live and VOD, and those are different rows to browse.
                    groups.getOrPut(kind.name + "\u0000" + name) {
                        MediaGroup(StableIds.group(sourceId, kind, name), name, kind)
                            .also(newGroups::add)
                    }
                }

                batch.add(toItem(sourceId, entry, classification, group?.id, order++))

                if (batch.size >= batchSize) {
                    imported += flush(batch, newGroups)
                    onProgress(ImportProgress.Importing(imported, groups.size, kind))
                    if (isCancelled()) cancelled = true
                }
            }

            imported += flush(batch, newGroups)

            val summary = ImportSummary(
                sourceId = sourceId,
                items = imported,
                groups = groups.size,
                skipped = stats.skipped,
                byKind = perKind.toKindMap(),
                durationMs = clock() - started,
                cancelled = cancelled,
            )

            if (cancelled) {
                // Everything committed so far stays: a cancelled import leaves a
                // partial catalogue, which is far better than an empty one.
                writer.abort(null)
            } else {
                writer.finish(summary)
                onProgress(ImportProgress.Done(imported, summary.durationMs))
            }
            return summary
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
    }

    /** Writes and commits one batch, then clears it for reuse. Returns rows written. */
    private fun flush(batch: MutableList<CatalogItem>, newGroups: MutableList<MediaGroup>): Int {
        if (batch.isEmpty() && newGroups.isEmpty()) return 0
        if (newGroups.isNotEmpty()) {
            // Groups first: an item must never reference a group that is not
            // there yet, or a join drops the row.
            writer.writeGroups(newGroups)
            newGroups.clear()
        }
        val written = batch.size
        if (written > 0) {
            writer.writeItems(batch)
            batch.clear()
        }
        writer.commit()
        return written
    }

    private fun toItem(
        sourceId: String,
        entry: M3uEntry,
        classification: Classification,
        groupId: String?,
        order: Int,
    ): CatalogItem {
        val kind = classification.kind
        val seriesTitle = classification.seriesTitle
        return CatalogItem(
            id = StableIds.item(sourceId, entry.url),
            sourceId = sourceId,
            kind = kind,
            // For an episode this is the episode's own title; the show name
            // lives in seriesTitle, so a season list reads correctly.
            title = classification.episodeTitle ?: entry.name,
            streamUrl = entry.url,
            artworkUrl = entry.logoUrl,
            groupId = groupId,
            epgChannelId = entry.tvgId,
            providerOrder = order,
            durationSeconds = entry.durationSeconds.takeIf { it > 0 },
            seriesId = seriesTitle?.let { StableIds.series(sourceId, it) },
            seriesTitle = seriesTitle,
            seasonNumber = classification.seasonNumber,
            episodeNumber = classification.episodeNumber,
        )
    }

    private fun IntArray.toKindMap(): Map<MediaKind, Int> {
        val map = LinkedHashMap<MediaKind, Int>(MediaKind.entries.size)
        for (kind in MediaKind.entries) {
            val count = this[kind.ordinal]
            if (count > 0) map[kind] = count
        }
        return map
    }

    companion object {
        /**
         * ~1,000 rows per transaction. Larger batches stop helping insert
         * throughput and start delaying the first visible content; smaller ones
         * pay the transaction cost too often on slow flash storage.
         */
        const val DEFAULT_BATCH = 1_000

        /** A batch is held in memory, so it cannot be unbounded. */
        const val MAX_BATCH = 5_000
    }
}

/**
 * Deterministic ids.
 *
 * Ids must be stable across re-imports: a user's favourites and watch progress
 * point at them, and a nightly playlist refresh must not orphan either. So an
 * id is a hash of what identifies the row, never a row number.
 *
 * FNV-1a, 64-bit, hex. Not a cryptographic hash and not meant to be — over
 * 400,000 rows the collision probability is around one in a billion, and a
 * collision merges two rows rather than corrupting anything.
 *
 * The stream URL is the item key because M3U offers nothing better: `tvg-id` is
 * routinely shared between SD and HD variants of the same channel. The tradeoff
 * is that Xtream URLs embed credentials, so a password change re-keys the
 * catalogue — which is why the Xtream path supplies the provider's own numeric
 * stream ids as the key instead of a URL.
 */
internal object StableIds {

    /**
     * Note what is *not* in here: the media kind. The classifier will keep
     * improving, and an entry that gets reclassified — a station finally
     * recognised as radio rather than live — must keep its id. Folding kind in
     * would silently drop that row's favourite and watch position the first time
     * a classification rule improved.
     */
    fun item(sourceId: String, key: String): String = hex(mix(seed(sourceId), key))

    fun group(sourceId: String, kind: MediaKind, name: String): String =
        hex(mix(mix(mix(mix(seed(sourceId), "g"), kind.name), SEPARATOR), name.lowercase()))

    /** Case- and spacing-insensitive, so `Breaking  Bad` folds into one show. */
    fun series(sourceId: String, title: String): String =
        hex(mix(mix(seed(sourceId), "s"), normalise(title)))

    private fun seed(sourceId: String): Long = mix(OFFSET_BASIS, sourceId)

    private fun mix(hash: Long, value: String): Long {
        var h = hash
        for (i in value.indices) h = (h xor value[i].code.toLong()) * PRIME
        return h
    }

    private fun mix(hash: Long, value: Char): Long = (hash xor value.code.toLong()) * PRIME

    private fun normalise(title: String): String {
        val sb = StringBuilder(title.length)
        var lastWasSpace = false
        for (c in title) {
            if (c.isWhitespace()) {
                if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
                lastWasSpace = true
            } else {
                sb.append(c.lowercaseChar())
                lastWasSpace = false
            }
        }
        return sb.toString().trimEnd()
    }

    private fun hex(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')

    /** Keeps `ab`+`c` from hashing the same as `a`+`bc`. */
    private const val SEPARATOR = '\u0000'

    private const val OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
    private const val PRIME = 1099511628211L
}
