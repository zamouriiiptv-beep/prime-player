package com.castivio.data.playlist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.Reader
import java.io.StringReader

/**
 * The playlist a device keeps: written as the download is read, kept only when it was
 * read to its end, and never the reason an import fails.
 */
class PlaylistCopyStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val playlist = buildString {
        appendLine("#EXTM3U")
        for (i in 0 until 2_000) {
            appendLine("""#EXTINF:-1 group-title="Sports",Channel $i""")
            appendLine("http://host/live/u/p/$i.ts")
        }
    }

    @Test
    fun `a playlist read to its end is kept, character for character`() {
        val store = PlaylistCopyStore(folder.root)

        val copy = store.begin(ID)
        assertEquals(playlist, copy.tee(StringReader(playlist)).readText())

        assertTrue(copy.keep())
        assertEquals(playlist, store.open(ID)!!.use(Reader::readText))
    }

    /** A download that failed, or was not needed, is not the playlist; the one before it is. */
    @Test
    fun `a dropped copy leaves the one kept before it`() {
        val store = PlaylistCopyStore(folder.root)
        keep(store, "#EXTM3U\nfirst\n")

        val copy = store.begin(ID)
        copy.tee(StringReader(playlist)).read(CharArray(100))
        copy.drop()

        assertEquals("#EXTM3U\nfirst\n", store.open(ID)!!.use(Reader::readText))
        assertEquals("nothing half written is left", listOf("$ID.m3u"), names())
    }

    /**
     * A full disk is a reason for the next section to download again, never for this one
     * to fail: the import reads every character it would have read, and the copy kept
     * before it goes too, because it is no longer the playlist the catalogue came from.
     */
    @Test
    fun `a copy the disk will not take is given up without the import noticing`() {
        val store = PlaylistCopyStore(folder.root)
        keep(store, "#EXTM3U\nolder\n")
        // Where the copy would be written, something it cannot be written over.
        File(folder.root, "$ID.m3u.part").mkdirs()

        val copy = store.begin(ID)
        assertEquals(playlist, copy.tee(StringReader(playlist)).readText())

        assertFalse(copy.keep())
        assertNull(store.open(ID))
    }

    @Test
    fun `an answer with no body leaves no file behind`() {
        val store = PlaylistCopyStore(folder.root)

        val copy = store.begin(ID)
        assertEquals("", copy.tee(StringReader("")).readText())

        assertFalse(copy.keep())
        assertTrue(names().isEmpty())
    }

    @Test
    fun `discarding forgets the copy, and one being written`() = runBlocking {
        val store = PlaylistCopyStore(folder.root)
        keep(store, playlist)
        store.begin(ID).tee(StringReader(playlist)).read(CharArray(100))

        store.discard(ID)

        assertNull(store.open(ID))
        assertTrue(names().isEmpty())
    }

    /** Whatever way the import moves past a character, the copy has it. */
    @Test
    fun `characters skipped or read one at a time are copied too`() {
        val store = PlaylistCopyStore(folder.root)
        val copy = store.begin(ID)
        val reader = copy.tee(StringReader(playlist))

        val first = reader.read().toChar()
        val skipped = reader.skip(10_000)
        val rest = reader.readText()

        assertEquals('#', first)
        assertEquals(10_000L, skipped)
        assertEquals(playlist.length, 1 + 10_000 + rest.length)
        assertTrue(copy.keep())
        assertEquals(playlist, store.open(ID)!!.use(Reader::readText))
    }

    @Test
    fun `a copy cannot be marked, so nothing is read twice into it`() {
        val reader = PlaylistCopyStore(folder.root).begin(ID).tee(StringReader(playlist))

        assertFalse(reader.markSupported())
        assertTrue(runCatching { reader.mark(10) }.isFailure)
    }

    /** Ids are hex hashes; anything else is spelled out, so no id can name a path outside. */
    @Test
    fun `an id that is not a hash cannot name a file outside the store`() {
        val store = PlaylistCopyStore(File(folder.root, "playlists"))

        keep(store, playlist, id = "../outside")

        assertEquals(listOf("playlists"), folder.root.list()!!.toList())
        assertEquals(playlist, store.open("../outside")!!.use(Reader::readText))
        assertNull(store.open("outside"))
    }

    private fun keep(store: PlaylistCopyStore, content: String, id: String = ID) {
        val copy = store.begin(id)
        copy.tee(StringReader(content)).readText()
        assertTrue(copy.keep())
    }

    private fun names(): List<String> = folder.root.list()!!.sorted()

    private companion object {
        const val ID = "9f86d081884c7d65"
    }
}
