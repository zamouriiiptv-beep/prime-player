package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The exception's name as a screen and a log line print it.
 *
 * Both print it from [FetchDiagnosis.nameOf], so the photograph of a failure and the
 * logcat line of the same failure name the same thing the same way.
 */
class FetchDiagnosisTest {

    @Test
    fun `an exception is named by its class`() {
        assertEquals("IllegalStateException", FetchDiagnosis.nameOf(IllegalStateException("boom")))
    }

    /** A wrapper alone says where it was caught; the root says what happened. */
    @Test
    fun `a wrapped exception names its root cause beside it`() {
        val wrapped = IOException("outer", IllegalArgumentException("inner"))

        assertEquals("IOException<-IllegalArgumentException", FetchDiagnosis.nameOf(wrapped))
    }

    @Test
    fun `the root is the deepest cause, not the next one`() {
        val deep = RuntimeException(IOException(IllegalStateException("root")))

        assertEquals("RuntimeException<-IllegalStateException", FetchDiagnosis.nameOf(deep))
    }

    /** `IOException<-IOException` is the same word twice and no more information. */
    @Test
    fun `a cause of the same class is not repeated`() {
        assertEquals("IOException", FetchDiagnosis.nameOf(IOException(IOException("again"))))
    }

    /**
     * A message can name the host and, for a playlist, the whole link with its password
     * in the query. Nothing of it may reach a screen or a log.
     */
    @Test
    fun `no part of a message is ever in the name`() {
        val secret = "http://host/get.php?username=u7sam9&password=p4ss9w0rd"
        val name = FetchDiagnosis.nameOf(IllegalArgumentException(secret, IOException(secret)))

        assertFalse(name, name.contains("u7sam9"))
        assertFalse(name, name.contains("p4ss9w0rd"))
        assertFalse(name, name.contains("host"))
    }

    @Test
    fun `an anonymous exception is named as one rather than by its message`() {
        val anonymous = object : RuntimeException("http://host/get.php?password=p4ss9w0rd") {}

        assertEquals("anonymous", FetchDiagnosis.nameOf(anonymous))
    }

    /** A cause chain that loops back on itself must not hang the screen that names it. */
    @Test
    fun `a cause chain that loops is still named`() {
        val first = IOException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertTrue(FetchDiagnosis.nameOf(first).startsWith("IOException"))
    }
}
