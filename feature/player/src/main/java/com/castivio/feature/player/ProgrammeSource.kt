package com.castivio.feature.player

import com.castivio.domain.EpgRepository
import dagger.Module
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the programme strip is filled from, after the picture is up.
 *
 * ## Why the player does not simply take an `EpgRepository`
 *
 * Because of what a narrower type makes impossible. `EpgRepository` can be asked for a
 * guide window across a hundred channels, and having it in the player's constructor is an
 * invitation to prefetch the neighbours "while we're here" — which is exactly the class of
 * work the performance contract forbids on this screen. This interface can answer one
 * question about one channel and nothing else.
 *
 * It also makes the timing testable. `PlayerPathTest` hands the view model a source that
 * records when it was called, and asserts it was not called before the first frame. Against
 * the full repository that assertion would be a guess about which of six methods might have
 * been used.
 */
interface ProgrammeSource {

    /**
     * What is on this channel now, or null.
     *
     * Null is an ordinary answer, not a failure: a provider with no guide, a channel the
     * XMLTV file does not carry, a guide that has not been imported yet. The strip stays a
     * skeleton and the player is unaffected — which is the property that makes live
     * playback independent of EPG rather than merely tolerant of it.
     */
    suspend fun now(channelId: String): Programme?
}

/**
 * The real one, over the stored guide.
 *
 * Reads what has already been imported and never fetches: a network request here would be
 * a network request the player started, and although it is after the first frame it is
 * still the player's business to not make it. Importing the guide is the sync job's work.
 */
@Singleton
class StoredProgrammes @Inject constructor(
    private val epg: EpgRepository,
) : ProgrammeSource {

    override suspend fun now(channelId: String): Programme? {
        val nowMs = System.currentTimeMillis()
        val entry = epg.nowNext(listOf(channelId), nowMs)[channelId] ?: return null
        val current = entry.now ?: return null
        return Programme(
            now = current.title,
            window = "${clock(current.startMs)} – ${clock(current.stopMs)}",
            next = entry.next?.title,
            progress = current.progressAt(nowMs),
        )
    }

    /**
     * Wall-clock, as digits.
     *
     * Formatted here rather than in composition because `CLAUDE.md` puts formatting in the
     * state holder, and because a strip that reformats two timestamps on every recomposition
     * is doing it 25 times a second to display the same two numbers.
     */
    private fun clock(epochMs: Long): String = LocalDateTime
        .ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .format(HHMM)

    private companion object {
        /**
         * The device's zone, not UTC.
         *
         * Arithmetic on the epoch directly is a whole hour wrong in most of Europe for
         * half the year, and a guide that says a programme started an hour ago is worse
         * than a guide that says nothing.
         */
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProgrammeModule {

    @Binds
    @Singleton
    abstract fun programmes(stored: StoredProgrammes): ProgrammeSource
}
