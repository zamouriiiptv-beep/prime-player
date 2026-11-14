package com.castivio.tv.probe.core

import com.castivio.data.parsing.JsonScanner
import java.io.Reader
import java.util.Arrays

/** A growable list of longs, so that a few hundred thousand ids are not a few hundred thousand objects. */
class LongList(initial: Int = 16) {
    private var values = LongArray(initial.coerceAtLeast(4))

    var size: Int = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun toArray(): LongArray = values.copyOf(size)
}

/**
 * The two things a film contributes to the check, and nothing else: its id, the category the walk asked for when
 * it found it, and the category the film says it is in.
 *
 * For the one reply the two categories are the same. Rows are kept as they came, repeats included -- a film listed
 * twice is two rows -- because counting them is the point.
 */
class FilmEntries(initialCapacity: Int = 1 shl 10) {
    private var ids = LongArray(initialCapacity.coerceAtLeast(16))
    private var requested = arrayOfNulls<String>(ids.size)
    private var declared = arrayOfNulls<String>(ids.size)

    var size: Int = 0
        private set

    fun add(id: Long, requestedCategory: String?, declaredCategory: String?) {
        if (size == ids.size) grow(size * 2)
        ids[size] = id
        requested[size] = requestedCategory
        declared[size] = declaredCategory
        size++
    }

    fun addAll(other: FilmEntries) {
        for (i in 0 until other.size) add(other.ids[i], other.requested[i], other.declared[i])
    }

    fun id(index: Int): Long = ids[index]

    fun requested(index: Int): String? = requested[index]

    fun declared(index: Int): String? = declared[index]

    fun idsCopy(): LongArray = ids.copyOf(size)

    private fun grow(capacity: Int) {
        ids = ids.copyOf(capacity)
        requested = requested.copyOf(capacity)
        declared = declared.copyOf(capacity)
    }
}

/**
 * A provider's `stream_id` as one number.
 *
 * A panel sends it as a number or as a string of one, and this makes both the same value. An id that is not a
 * non-negative whole number is kept apart from every number by hashing it with the top bit set, so two different
 * odd ids never meet a real one.
 */
object StreamKey {

    fun of(raw: String): Long {
        val text = raw.trim()
        val number = text.toLongOrNull()
        if (number != null && number >= 0L) return number
        var hash = FNV_OFFSET
        for (c in text) hash = (hash xor c.code.toLong()) * FNV_PRIME
        return hash or Long.MIN_VALUE
    }

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}

/** Makes one string of every repeat of a category id, so two hundred thousand rows hold a few hundred. */
class StringPool {
    private val strings = HashMap<String, String>()

    fun of(value: String?): String? {
        if (value == null) return null
        return strings.getOrPut(value) { value }
    }
}

/** The distinct values of an array of ids, sorted, to be asked "is this one here?" by binary search. */
class SortedIds private constructor(
    private val values: LongArray,
    val size: Int,
) {
    operator fun contains(key: Long): Boolean = indexOf(key) >= 0

    /** The place of [key], or a negative number when it is not here. */
    fun indexOf(key: Long): Int = Arrays.binarySearch(values, 0, size, key)

    fun at(index: Int): Long = values[index]

    companion object {
        fun of(ids: LongArray): SortedIds {
            val copy = ids.copyOf()
            Arrays.sort(copy)
            var unique = 0
            var previous = 0L
            for (value in copy) {
                if (unique == 0 || value != previous) {
                    copy[unique++] = value
                    previous = value
                }
            }
            return SortedIds(copy, unique)
        }
    }
}

/** What the one reply held: every row's id and category, and the categories the panel listed in the same run. */
class OneReplyScan(
    val entries: FilmEntries,
    val listed: Set<String>,
    val rowsWithoutId: Int,
)

/**
 * What a walk brought: each row with the category it was asked under and the one it names.
 *
 * [failed] holds the categories whose request did not end well, so that films missing from them are told apart
 * from films the walk simply could not reach.
 */
class WalkScan(
    val entries: FilmEntries,
    val listed: List<String>,
    val failed: Set<String>,
    val rowsWithoutId: Int,
)

/**
 * Reads a `get_vod_streams` reply for **two fields of each film -- `stream_id` and `category_id` -- and nothing
 * else.**
 *
 * It uses the importer's own [JsonScanner], so a number sent as a string and a string sent as a number are read
 * as the importer reads them, and every other member of the film is stepped over unread. Nothing of the film is
 * built or kept: no name, no address, no picture. A row without an id is counted and left out, as the importer
 * leaves it out.
 *
 * The one thing the importer also asks of a row is a name. This does not read it, so it counts a row the
 * importer would drop for having none; the report says so.
 */
object FilmFieldScanner {

    class Counts(val films: Int, val withoutId: Int)

    /**
     * @param requestedCategory the category the reply was asked for; null for the one reply, whose rows are
     *   filed under the category they name.
     * @throws com.castivio.data.parsing.JsonFormatException when the reply is not a list.
     */
    fun scan(reader: Reader, requestedCategory: String?, pool: StringPool, into: FilmEntries): Counts {
        val scanner = JsonScanner(reader)
        var films = 0
        var withoutId = 0
        scanner.readArray {
            var streamId: String? = null
            var categoryId: String? = null
            scanner.readObject { field ->
                when (field) {
                    "stream_id" -> streamId = scanner.string()
                    "category_id" -> categoryId = scanner.string()
                }
            }
            val id = streamId
            if (id == null) {
                withoutId++
            } else {
                val declared = pool.of(categoryId)
                into.add(StreamKey.of(id), requestedCategory ?: declared, declared)
                films++
            }
        }
        return Counts(films, withoutId)
    }
}
