package com.castivio.tv.debug.lab

import com.castivio.domain.CatalogItem
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import java.security.MessageDigest

/**
 * A synthetic live catalogue to write: its size, its categories, and the seed that fixes every value.
 *
 * Nothing in it is real. Names are made of pseudo-words, addresses are `example.invalid` with placeholder
 * path segments, and no identifier comes from a provider or a subscription.
 */
internal data class LabDatasetSpec(
    val name: String,
    val rows: Int,
    val groups: Int,
    val seed: Long,
    /** The share of rows that carry an `epg_channel_id`; real panels vary, so it is a parameter. */
    val epgFill: Double,
) {
    companion object {
        /** A few seconds: to see the lab work on a device before committing to the long run. */
        val SMOKE = LabDatasetSpec("smoke-3k", 3_000, 120, 7L, 0.70)

        /** The size of a catalogue measured on a real device: 18,803 channels in 487 categories. */
        val MAIN_18K = LabDatasetSpec("main-18k", 18_803, 487, 18L, 0.70)

        /** And the larger one: 55,801 channels in 913 categories. */
        val MAIN_55K = LabDatasetSpec("main-55k", 55_801, 913, 55L, 0.70)
    }
}

/**
 * The deterministic source of every value in a dataset: a counter-based generator (SplitMix64), so row `i`
 * is a pure function of the seed and `i`. That is what lets every case of a run, and every round, write
 * byte-identical rows without holding the catalogue in memory -- a batch is built when it is needed and
 * dropped once written.
 */
internal object LabMix {
    private val GOLDEN = java.lang.Long.parseUnsignedLong("9E3779B97F4A7C15", 16)
    private val STEP_1 = java.lang.Long.parseUnsignedLong("BF58476D1CE4E5B9", 16)
    private val STEP_2 = java.lang.Long.parseUnsignedLong("94D049BB133111EB", 16)
    private const val SALT_STEP = 0x632BE59BD9B4E019L

    /** The SplitMix64 finaliser: a bijection on 64 bits, so distinct states give distinct values. */
    fun mix(state: Long): Long {
        var z = state
        z = (z xor (z ushr 30)) * STEP_1
        z = (z xor (z ushr 27)) * STEP_2
        return z xor (z ushr 31)
    }

    /** The value for ([index], [salt]) under [seed]; with one salt, distinct indices never collide. */
    fun hash(seed: Long, index: Long, salt: Int): Long =
        mix(seed + (index + 1) * GOLDEN + salt.toLong() * SALT_STEP)

    /** A value in [0, 1). */
    fun unit(value: Long): Double = (value ushr 11).toDouble() / 9007199254740992.0

    /** A value in [0, bound). */
    fun below(value: Long, bound: Int): Int = ((value ushr 1) % bound).toInt()

    /** A short run of draws from one starting point, for the several values a row needs. */
    class Bits(private var state: Long) {
        fun long(): Long {
            state += GOLDEN
            return mix(state)
        }

        fun below(bound: Int): Int = LabMix.below(long(), bound)

        fun unit(): Double = LabMix.unit(long())
    }
}

/** The same bytes in the same order on both sides of a comparison, so two digests are equal or they differ. */
internal class LabDigest {
    private val md = MessageDigest.getInstance("SHA-256")

    fun field(value: String?) {
        md.update((value ?: NULL).toByteArray(Charsets.UTF_8))
        md.update(SEP)
    }

    fun endRow() {
        md.update(EOL)
    }

    fun hex(): String {
        val bytes = md.digest()
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return out.toString()
    }

    private companion object {
        const val NULL = "\u0000"
        val SEP = byteArrayOf(0x1F)
        val EOL = byteArrayOf(0x0A)
        const val HEX = "0123456789abcdef"
    }
}

/**
 * The rows of one [LabDatasetSpec], made on demand.
 *
 * Shaped like the catalogues measured on real devices, by assumption and not by copy: a few hundred
 * categories whose sizes fall off like a power law, a provider listing channels in category runs of about
 * eight, titles of two kinds (a fifth Arabic, the rest Latin with the country tags and quality suffixes
 * providers add), a `provider_order` that counts up with small gaps, and ids that are 16 hex characters of
 * a hash and therefore arrive in no useful order -- like the real ones. Every row is a live channel under
 * one subscription and one generation, as a first load of the channels is.
 */
internal class LabDataset(val spec: LabDatasetSpec) {

    val rows: Int get() = spec.rows

    val sourceId: String = hex16(LabMix.hash(spec.seed, 0, SALT_SOURCE))

    private val groupIds: Array<String> = Array(spec.groups) { hex16(LabMix.hash(spec.seed, it.toLong(), SALT_GROUP)) }

    val groups: List<MediaGroup> = groupIds.mapIndexed { index, id -> MediaGroup(id, "Group $index", MediaKind.LIVE) }

    /** The category of each row, by position in [groupIds]. */
    private val groupOf = IntArray(spec.rows)

    /** `provider_order` of each row. */
    private val order = IntArray(spec.rows)

    private val epgPool = maxOf(1, (spec.rows * spec.epgFill * 0.85).toInt())

    init {
        require(spec.rows > 0 && spec.groups > 0) { "an empty dataset" }
        require(groupIds.toSet().size == groupIds.size) { "category ids collided; change the seed" }

        // Zipf-like category sizes.
        val cumulative = DoubleArray(spec.groups)
        var sum = 0.0
        for (i in 0 until spec.groups) {
            sum += 1.0 / StrictMath.pow((i + 1).toDouble(), 0.8)
            cumulative[i] = sum
        }
        // A provider lists channels in runs of one category: an exponential run length, mean about 8.
        var row = 0
        var step = 0L
        while (row < spec.rows) {
            val pick = LabMix.unit(LabMix.hash(spec.seed, step, SALT_RUN_GROUP)) * sum
            var lo = 0
            var hi = spec.groups - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (cumulative[mid] > pick) hi = mid else lo = mid + 1
            }
            val length = 1 + (-StrictMath.log(1.0 - LabMix.unit(LabMix.hash(spec.seed, step, SALT_RUN_LENGTH))) * 8.0).toInt()
            val run = minOf(spec.rows - row, length)
            for (k in 0 until run) {
                groupOf[row] = lo
                row++
            }
            step++
        }
        // `provider_order`: counts up, with a gap now and then.
        var counter = 0
        for (i in 0 until spec.rows) {
            val gap = LabMix.hash(spec.seed, i.toLong(), SALT_GAP)
            counter += 1 + (if (LabMix.unit(gap) < 0.02) 1 + LabMix.below(LabMix.mix(gap), 5) else 0)
            order[i] = counter
        }
    }

    /** Row [i] of the catalogue, always the same. */
    fun item(i: Int): CatalogItem {
        val index = i.toLong()
        val bits = LabMix.Bits(LabMix.hash(spec.seed, index, SALT_ROW))
        val title = title(bits)
        val artwork = if (bits.unit() < 0.7) {
            "http://img.example.invalid/l/" + hex8(bits.long()) + "/" + hex8(bits.long()) + ".png"
        } else {
            null
        }
        val streamId = 1000L + index * 21 + LabMix.below(LabMix.hash(spec.seed, index, SALT_STREAM), 20)
        val epgBits = LabMix.Bits(LabMix.hash(spec.seed, index, SALT_EPG))
        val epg = if (epgBits.unit() < spec.epgFill) {
            "epg" + epgBits.below(epgPool) + "." + COUNTRIES[epgBits.below(COUNTRIES.size)]
        } else {
            null
        }
        return CatalogItem(
            id = hex16(LabMix.hash(spec.seed, index, SALT_ID)),
            sourceId = sourceId,
            kind = MediaKind.LIVE,
            title = title,
            streamUrl = "http://s" + (1 + bits.below(9)) + ".example.invalid:8080/live/user_x/pass_x/" + streamId + ".ts",
            artworkUrl = artwork,
            groupId = groupIds[groupOf[i]],
            epgChannelId = epg,
            providerRef = streamId.toString(),
            providerOrder = order[i],
        )
    }

    /** Rows [from, to): one batch, built just before it is written and not kept. */
    fun items(from: Int, to: Int): List<CatalogItem> {
        val batch = ArrayList<CatalogItem>(to - from)
        for (i in from until to) batch.add(item(i))
        return batch
    }

    /**
     * What the database must hold in the columns this lab writes itself, as a digest, in insertion order.
     * The two folded columns (`sort_title`, `search_text`) are the writer's own work and are compared
     * across cases instead -- see [LabRunner].
     */
    val expectedDigest: String by lazy {
        val digest = LabDigest()
        for (i in 0 until spec.rows) {
            val item = item(i)
            digest.field(item.id)
            digest.field(item.sourceId)
            digest.field(item.kind.name)
            digest.field(item.title)
            digest.field(item.streamUrl)
            digest.field(item.artworkUrl)
            digest.field(item.groupId)
            digest.field(item.epgChannelId)
            digest.field(item.providerRef)
            digest.field(item.providerOrder.toString())
            digest.field(null) // duration_seconds
            digest.field(null) // series_id
            digest.field(null) // series_title
            digest.field(null) // season_number
            digest.field(null) // episode_number
            digest.field(GENERATION.toString())
            digest.field(NOW.toString())
            digest.endRow()
        }
        digest.hex()
    }

    /** How many rows each category holds, by category id: what `item_count` must say after the import. */
    val expectedGroupCounts: Map<String, Int> by lazy {
        val counts = HashMap<String, Int>()
        for (i in 0 until spec.rows) {
            val id = groupIds[groupOf[i]]
            counts[id] = (counts[id] ?: 0) + 1
        }
        counts
    }

    private fun title(bits: LabMix.Bits): String {
        val out = StringBuilder()
        if (bits.unit() < 0.2) {
            val words = 1 + bits.below(3)
            for (w in 0 until words) {
                if (w > 0) out.append(' ')
                val length = 3 + bits.below(5)
                for (c in 0 until length) out.append(ARABIC[bits.below(ARABIC.length)])
            }
        } else {
            val tag = TAGS[bits.below(TAGS.size)]
            if (tag.isNotEmpty()) out.append(tag).append(' ')
            val words = 1 + bits.below(3)
            for (w in 0 until words) {
                if (w > 0) out.append(' ')
                out.append(WORDS[bits.below(WORDS.size)])
            }
            if (bits.unit() < 0.3) out.append(' ').append(1 + bits.below(99))
            val quality = QUALITY[bits.below(QUALITY.size)]
            if (quality.isNotEmpty()) out.append(' ').append(quality)
        }
        return out.toString()
    }

    companion object {
        /** The instant written as `added_at`, injected into the writer so it is the same in every case. */
        const val NOW = 1_700_000_000_000L

        /** A first load writes generation 1. */
        const val GENERATION = 1L

        private const val SALT_SOURCE = 1
        private const val SALT_GROUP = 2
        private const val SALT_RUN_GROUP = 3
        private const val SALT_RUN_LENGTH = 4
        private const val SALT_GAP = 5
        private const val SALT_ROW = 6
        private const val SALT_STREAM = 7
        private const val SALT_EPG = 8
        private const val SALT_ID = 9

        private const val ARABIC = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
        private val COUNTRIES = arrayOf("fr", "uk", "ar", "de", "es")
        private val TAGS = arrayOf("|FR|", "|AR|", "|UK|", "|DE|", "|ES|", "|IT|", "|US|", "|TR|", "|PT|", "[HD]", "[4K]", "AR:", "FR -", "")
        private val QUALITY = arrayOf("HD", "FHD", "4K", "SD", "", "")

        /** 2,500 pseudo-words, the same for every dataset. */
        private val WORDS: Array<String> = run {
            val consonants = "bcdfghjklmnprstvwz"
            val vowels = "aeiou"
            val bits = LabMix.Bits(0x5EEDL)
            Array(2_500) {
                val word = StringBuilder()
                val syllables = 2 + bits.below(3)
                for (s in 0 until syllables) {
                    word.append(consonants[bits.below(consonants.length)])
                    word.append(vowels[bits.below(vowels.length)])
                }
                word.setCharAt(0, word[0].uppercaseChar())
                word.toString()
            }
        }

        private fun hex16(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')

        private fun hex8(value: Long): String = Integer.toHexString((value ushr 32).toInt()).padStart(8, '0')
    }
}
