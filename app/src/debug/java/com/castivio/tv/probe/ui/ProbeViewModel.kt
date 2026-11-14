package com.castivio.tv.probe.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.data.database.CastivioDatabase
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.tv.probe.core.Fmt
import com.castivio.tv.probe.core.NetworkSnapshot
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbePlan
import com.castivio.tv.probe.core.ProbeReport
import com.castivio.tv.probe.core.Redaction
import com.castivio.tv.probe.core.SessionHeader
import com.castivio.tv.probe.core.SessionState
import com.castivio.tv.probe.core.SessionStatus
import com.castivio.tv.probe.run.HttpRunExecutor
import com.castivio.tv.probe.run.ProbeClient
import com.castivio.tv.probe.run.ProbeCredentials
import com.castivio.tv.probe.session.ProbeSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the screen shows: lines above the buttons, one line of progress, the report, and which buttons work. */
data class ProbeUi(
    val headline: List<String>,
    val progress: String,
    val report: String,
    val canStart: Boolean,
    val canContinue: Boolean,
    val canStop: Boolean,
    val canCopy: Boolean,
)

/**
 * Holds one probe session for the screen.
 *
 * ## What it is allowed to touch
 *
 * The active subscription, **read once**, through [SourceRepository.activeNow]: it never calls `save`, `register`,
 * `setActive` or anything else that writes. The account goes straight into a [ProbeCredentials], which keeps it in
 * memory and says nothing when printed. The session runs on its own threads and writes nothing anywhere.
 *
 * Every report that leaves this class has been scrubbed of the account's strings, whatever built it.
 */
@HiltViewModel
class ProbeViewModel @Inject constructor(
    private val sources: SourceRepository,
    private val database: CastivioDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val config = ProbeConfig()
    private val plan = ProbePlan.standard(config.repeats)

    private val mutableUi = MutableStateFlow(
        ProbeUi(
            headline = emptyList(),
            progress = "Reading the active subscription...",
            report = "",
            canStart = false,
            canContinue = false,
            canStop = false,
            canCopy = false,
        ),
    )
    val ui: StateFlow<ProbeUi> = mutableUi.asStateFlow()

    private var credentials: ProbeCredentials? = null
    private var environment: AndroidProbeEnvironment? = null
    private var session: ProbeSession? = null
    private var headline: List<String> = emptyList()

    init {
        viewModelScope.launch { prepare() }
    }

    private suspend fun prepare() {
        // Read diagnostics before activeNow(): that method may promote the oldest saved source.
        // The probe must report the database as it was found, without changing it for diagnosis.
        val databaseLines = withContext(Dispatchers.IO) { databaseDiagnostics() }
        val source = withContext(Dispatchers.IO) { sources.activeNow() }
        if (source == null) {
            unavailable("There is no active subscription.", databaseLines)
            return
        }
        if (source.kind != SourceKind.XTREAM) {
            unavailable("The active subscription is not an Xtream account.", databaseLines)
            return
        }
        val url = source.url
        val user = source.username
        val pass = source.password
        if (url.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) {
            unavailable("The Xtream account has no address or no credentials.", databaseLines)
            return
        }
        val account = ProbeCredentials(url, user, pass)
        val platform = AndroidProbeEnvironment(context)
        credentials = account
        environment = platform
        headline = databaseLines + headlineOf(account, platform.network())
        mutableUi.value = ProbeUi(
            headline = headline,
            progress = "Ready. Press Start.",
            report = "",
            canStart = true,
            canContinue = false,
            canStop = false,
            canCopy = false,
        )
    }

    private fun unavailable(message: String, databaseLines: List<String> = emptyList()) {
        mutableUi.value = ProbeUi(
            headline = databaseLines,
            progress = message,
            report = "",
            canStart = false,
            canContinue = false,
            canStop = false,
            canCopy = false,
        )
    }

    /**
     * A read-only snapshot of the exact Room file used by the app.
     *
     * Do not select source rows: they contain provider URLs and credentials. Only aggregate
     * counts and protocol names are safe and sufficient to distinguish a missing source row
     * from a catalogue that remains on disk.
     */
    private fun databaseDiagnostics(): List<String> = runCatching {
        val file = context.getDatabasePath(CastivioDatabase.NAME)
        val db = database.openHelper.readableDatabase
        fun scalar(sql: String): Long = db.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        fun grouped(sql: String): List<String> = db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("${cursor.getString(0)}=${cursor.getLong(1)}")
                }
            }
        }

        val sourceCount = scalar("SELECT COUNT(*) FROM source")
        val activeCount = scalar("SELECT COUNT(*) FROM source WHERE is_active = 1")
        val sourceKinds = grouped("SELECT kind, COUNT(*) FROM source GROUP BY kind ORDER BY kind")
        val mediaCount = scalar("SELECT COUNT(*) FROM media")
        val mediaKinds = grouped("SELECT kind, COUNT(*) FROM media GROUP BY kind ORDER BY kind")
        val groupCount = scalar("SELECT COUNT(*) FROM media_group")

        buildList {
            add("Database: ${CastivioDatabase.NAME}")
            add("Database file: ${if (file.exists()) "present" else "missing"} · ${file.length()} bytes")
            add("Database path: ${file.absolutePath}")
            add("Subscriptions: total=$sourceCount · active=$activeCount")
            add("Subscription kinds: ${sourceKinds.ifEmpty { listOf("none") }.joinToString()}")
            add("Catalogue: media=$mediaCount · groups=$groupCount")
            add("Media kinds: ${mediaKinds.ifEmpty { listOf("none") }.joinToString()}")
        }
    }.getOrElse { error ->
        listOf(
            "Database: ${CastivioDatabase.NAME}",
            "Database diagnostics failed: ${error::class.java.simpleName}",
        )
    }

    private fun headlineOf(account: ProbeCredentials, network: NetworkSnapshot): List<String> {
        val lines = ArrayList<String>()
        val metered = when (network.metered) {
            true -> " · METERED"
            false -> " · unmetered"
            null -> ""
        }
        lines += "Source: Xtream · ${account.scheme}"
        lines += "Network: ${network.transport}$metered"
        lines += "Expected data: ${Fmt.megabytes(plan.fullDownloads * config.priorReplyBytes)} MB " +
            "(${plan.fullDownloads} full downloads of about ${Fmt.megabytes(config.priorReplyBytes)} MB)"
        lines += "About 40 minutes with the screen on. Stop the Castivio app first, so nothing else uses the network."
        if (network.metered == true) lines += "WARNING: this network is metered."
        return lines
    }

    fun start() {
        val account = credentials ?: return
        val platform = environment ?: return
        if (session != null) return
        val header = SessionHeader(ProbeClient.USER_AGENT, account.scheme, platform.network().note())
        val running = ProbeSession(plan, HttpRunExecutor(account, platform, config), config, header, Dispatchers.IO)
        session = running
        viewModelScope.launch(Dispatchers.Default) { running.run() }
        viewModelScope.launch(Dispatchers.Default) {
            running.state.collect { state -> mutableUi.value = render(state, account) }
        }
    }

    fun resume() {
        session?.resume()
    }

    fun stop() {
        session?.stop()
    }

    override fun onCleared() {
        session?.stop()
    }

    private fun render(state: SessionState, account: ProbeCredentials): ProbeUi {
        val report = Redaction.scrub(ProbeReport.render(state), account.secrets())
        val paused = state.status == SessionStatus.PAUSED_PILOT || state.status == SessionStatus.PAUSED_BUDGET
        val over = state.status == SessionStatus.STOPPED || state.status == SessionStatus.FINISHED
        return ProbeUi(
            headline = headline,
            progress = progressText(state),
            report = report,
            canStart = false,
            canContinue = paused,
            canStop = !over,
            canCopy = true,
        )
    }

    private fun progressText(state: SessionState): String {
        val total = state.plan.fullDownloads
        val current = state.current
        return when (state.status) {
            SessionStatus.IDLE -> "Ready."
            SessionStatus.RUNNING ->
                if (current != null) {
                    "Run ${state.completedSteps + 1}/$total · ${current.label} · ${Fmt.seconds(current.elapsedMs)} s · " +
                        "${Fmt.megabytes(current.bytes)} MB · ${current.inFlight} in flight"
                } else {
                    "Starting step ${state.completedSteps + 1}/$total"
                }
            SessionStatus.COOLING -> "Cool-down ${state.coolingSeconds} s before step ${state.completedSteps + 1}/$total"
            SessionStatus.PAUSED_PILOT -> "Pilot done. Read the report below, then Continue or Stop."
            SessionStatus.PAUSED_BUDGET -> "More data than announced. Continue only if you accept it."
            SessionStatus.STOPPED -> "Stopped. The report below is what was measured."
            SessionStatus.FINISHED -> "Finished. Copy the report."
        }
    }
}
