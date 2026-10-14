package com.castivio.data.playlist

import com.castivio.core.common.AppError
import com.castivio.data.networking.HttpStatusException
import com.castivio.data.parsing.JsonFormatException
import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.FetchRoute
import com.castivio.domain.FetchStage
import java.io.IOException

/**
 * How a panel's API import failed, for the screen and for `adb logcat -s CastivioNet`.
 *
 * The Xtream counterpart of [M3uImportReport], and under its rule: a stage, a status, class
 * names and counts — never the address, the account, or an exception's message. A panel
 * URL carries the username and password in its query and in every stream path, and a
 * message is where an unknown host or a refused request quotes it.
 *
 * The API path had no such report: its failures reached the screen as one sentence, and
 * a refusal by status read as "no network" because the exception that carries the status
 * is an `IOException`. An M3U subscription read through its panel takes this path first,
 * so its failures need the same figures the playlist's have.
 */
internal object XtreamImportReport {

    /**
     * @param savingFailed whether the database writer threw, rather than the API or the
     *   parsing of its answer.
     * @param receivedBytes what the window's calls brought back; null when none completed.
     * @param itemsRead items the engine had handed to the database, committed or not.
     */
    fun diagnosisOf(
        error: Throwable,
        savingFailed: Boolean,
        receivedBytes: Long?,
        itemsRead: Int,
        itemsSaved: Int,
    ): FetchDiagnosis = FetchDiagnosis(
        stage = when {
            savingFailed -> FetchStage.SAVE
            // The panel answered, with a status that is not the API's reply.
            error is HttpStatusException -> FetchStage.ANSWER
            // It answered with something that is not the API's JSON.
            error is JsonFormatException -> FetchStage.READ
            // OkHttp would not make the base into a request: nothing was sent.
            error is IllegalArgumentException -> FetchStage.PREPARE
            // No connection, no name, no answer in time, a reset.
            error is IOException -> FetchStage.CONNECT
            // The engine itself threw while turning an answer into rows.
            else -> FetchStage.READ
        },
        httpStatus = (error as? HttpStatusException)?.statusCode,
        exception = FetchDiagnosis.nameOf(error),
        bytesReceived = receivedBytes,
        itemsRead = itemsRead,
        itemsSaved = itemsSaved,
        route = FetchRoute.XTREAM,
    )

    /** One line, in the shape of the playlist's, for the same log. */
    fun line(error: AppError, diagnosis: FetchDiagnosis, tookMs: Long): String = buildString {
        append("Xtream import failed")
        append(" stage=").append(diagnosis.stage.name)
        append(" error=").append(error.name)
        append(" http=").append(diagnosis.httpStatus?.toString() ?: NONE)
        append(" exception=").append(diagnosis.exception ?: NONE)
        append(" bytes=").append(diagnosis.bytesReceived?.toString() ?: NONE)
        append(" read=").append(diagnosis.itemsRead)
        append(" saved=").append(diagnosis.itemsSaved)
        append(" took=").append(tookMs).append("ms")
    }

    private const val NONE = "-"
}
