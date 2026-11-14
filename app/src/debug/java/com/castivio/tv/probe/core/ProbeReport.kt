package com.castivio.tv.probe.core

/**
 * The session as text a person can read on a phone and paste into a message.
 *
 * ## What is never in it
 *
 * No address, no user, no password, no port, no connection's remote end: it is built from [SessionState], which
 * holds none of them, and the view model scrubs the finished text of the tool's secrets besides (see [Redaction]).
 * The one thing printed about the address is its scheme. Failures are named by [Outcome], not by the exception's
 * own text, which can carry the host. Categories are named by the provider's id, never by name.
 *
 * Digits are Western whatever the device's language ([Fmt]). English throughout, like the strip: this is a
 * developer's instrument and is not translated.
 */
object ProbeReport {

    const val VERSION = 1

    fun render(state: SessionState): String {
        val lines = ArrayList<String>()
        lines += titleLines(state)
        lines += ""
        lines += runLines(state)
        lines += ""
        lines += armLines(state)
        lines += ""
        lines += parallelLines(state)
        lines += ""
        lines += completenessLines(state)
        lines += ""
        lines += decisionLines(state)
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------------------------------- title

    private fun titleLines(state: SessionState): List<String> {
        val net = state.header.network
        val plan = state.plan
        val changed = if (net.changed) " · NETWORK CHANGED" else ""
        val lines = ArrayList<String>()
        lines += "CASTIVIO PROBE v$VERSION · movies · network only"
        lines += "ua ${state.header.userAgent} · ${state.header.scheme} · ${net.transport}${meteredText(net.metered)}$changed"
        lines += "plan ${plan.repeats} repeats of K1 K3 K4 K8 + W1, 4 verifications, 1 drift check = " +
            "${plan.fullDownloads} full downloads"
        lines += "status ${statusText(state)} · step ${state.completedSteps}/${plan.fullDownloads}"
        lines += budgetText(state.budget)
        val stop = state.stop
        if (stop != null) lines += stopText(stop)
        return lines
    }

    private fun meteredText(metered: Boolean?): String = when (metered) {
        true -> " · metered"
        false -> " · unmetered"
        null -> ""
    }

    private fun statusText(state: SessionState): String = when (state.status) {
        SessionStatus.IDLE -> "READY"
        SessionStatus.RUNNING -> "RUNNING"
        SessionStatus.COOLING -> "COOLING ${state.coolingSeconds} s"
        SessionStatus.PAUSED_PILOT -> "PAUSED after the pilot: read the errors, the parallelism and the completeness, then continue or stop"
        SessionStatus.PAUSED_BUDGET -> "PAUSED data alert: more data than announced"
        SessionStatus.STOPPED -> "STOPPED"
        SessionStatus.FINISHED -> "FINISHED"
    }

    private fun budgetText(budget: BudgetSnapshot): String {
        val alert = when (budget.level) {
            DataBudget.Level.WITHIN -> ""
            DataBudget.Level.SOFT -> " · ALERT: more than announced"
            DataBudget.Level.HARD -> " · LIMIT reached"
        }
        return "data ${Fmt.megabytes(budget.receivedBytes)} MB received of ${Fmt.megabytes(budget.announcedBytes)} MB announced · " +
            "projected ${Fmt.megabytes(budget.projectedBytes)} MB$alert"
    }

    private fun stopText(stop: StopReason): String = when (stop) {
        is StopReason.ProviderStatus -> "STOPPED BY THE PROVIDER: HTTP ${stop.status} during ${stop.step}"
        is StopReason.Configuration -> "STOPPED: HTTP ${stop.status} on an essential request during ${stop.step} (check the subscription)"
        StopReason.ByUser -> "STOPPED by the user"
        is StopReason.HardBudget ->
            "STOPPED: ${Fmt.megabytes(stop.receivedBytes)} MB received is past the limit for ${Fmt.megabytes(stop.announcedBytes)} MB announced"
        is StopReason.ConsecutiveInvalid -> "STOPPED: ${stop.count} invalid runs in a row; look at the conditions, not the provider"
        is StopReason.Unexpected -> "STOPPED: unexpected ${stop.kind}"
    }

    // -------------------------------------------------------------------------------------------- runs

    private fun runLines(state: SessionState): List<String> {
        val lines = ArrayList<String>()
        lines += "RUNS (time: first request to last byte; MB = 1048576 bytes)"
        if (state.runs.isEmpty()) {
            lines += "none yet"
            return lines
        }
        for (run in state.runs) lines += runLine(run)
        return lines
    }

    private fun runLine(run: RunRecord): String {
        val parts = ArrayList<String>()
        val timed = run.step.purpose == StepPurpose.TIMED
        parts += run.step.label
        if (timed) {
            parts += "${Fmt.seconds2(run.totalMs)} s"
            val rate = run.megabytesPerSecond
            if (rate != null) parts += "${Fmt.decimal(rate, 2)} MB/s"
        } else {
            parts += "not timed"
        }
        val wire = run.wireBytes?.let { " (wire ${Fmt.megabytes(it)})" }.orEmpty()
        parts += "${Fmt.megabytes(run.bytes)} MB$wire"
        parts += "req ${run.requests.size}"
        parts += failureText(run)
        if (timed) parts += firstByteText(run)
        val categories = run.categoriesListed
        if (categories != null) parts += "cats $categories"
        if (run.step.arm.workers > 1) {
            val e = run.evidence
            parts += "sockets ${e.connectionsWithBytes}/${run.step.arm.workers} peak ${e.peakActiveConnections} full ${Fmt.seconds(e.msAtFullWorkers)} s"
        }
        parts += if (run.valid) {
            "valid"
        } else {
            "INVALID(${run.invalid.joinToString(",") { it.name.lowercase() }})"
        }
        return parts.joinToString(" · ")
    }

    private fun failureText(run: RunRecord): String {
        val failed = run.requests.filter { it.outcome != Outcome.OK }
        if (failed.isEmpty()) return "fail 0"
        val counts = LinkedHashMap<String, Int>()
        for (request in failed) {
            val key = if (request.outcome == Outcome.HTTP) "HTTP ${request.status ?: 0}" else request.outcome.name
            counts[key] = (counts[key] ?: 0) + 1
        }
        return "fail ${failed.size} (${counts.entries.joinToString(", ") { "${it.key} x${it.value}" }})"
    }

    private fun firstByteText(run: RunRecord): String {
        val seconds = run.filmRequests.mapNotNull { it.firstByteMs }.map { it / 1000.0 }
        val average = ProbeStats.mean(seconds) ?: return "first byte n/a"
        val p95 = ProbeStats.percentile(seconds, 95.0) ?: return "first byte n/a"
        return "first byte avg ${Fmt.decimal(average, 2)} s p95 ${Fmt.decimal(p95, 2)} s (n=${seconds.size})"
    }

    // --------------------------------------------------------------------------------------------- arms

    private fun armLines(state: SessionState): List<String> {
        val lines = ArrayList<String>()
        lines += "ARMS (valid timed runs; first byte over film requests only)"
        val timed = state.runs.filter { it.step.purpose == StepPurpose.TIMED }
        val baselineMedian = ProbeStats.median(
            timed.filter { it.step.arm == ProbeArms.K1 && it.valid }.map { it.totalMs.toDouble() },
        )
        for (arm in ProbeArms.all) {
            val runs = timed.filter { it.step.arm == arm }
            if (runs.isEmpty()) {
                lines += "${arm.id} · not run yet"
                continue
            }
            val valid = runs.filter { it.valid }
            val times = valid.map { it.totalMs.toDouble() }
            val median = ProbeStats.median(times)
            val parts = ArrayList<String>()
            parts += arm.id
            parts += "n=${valid.size}/${runs.size}"
            if (median != null) {
                val low = ProbeStats.minimum(times) ?: median
                val high = ProbeStats.maximum(times) ?: median
                parts += "total median ${Fmt.seconds(median)} s (${Fmt.seconds(low)}-${Fmt.seconds(high)})"
            }
            val rate = ProbeStats.median(valid.mapNotNull { it.megabytesPerSecond })
            if (rate != null) parts += "${Fmt.decimal(rate, 2)} MB/s median"
            val firstBytes = valid.flatMap { run -> run.filmRequests.mapNotNull { it.firstByteMs } }.map { it / 1000.0 }
            val average = ProbeStats.mean(firstBytes)
            val p95 = ProbeStats.percentile(firstBytes, 95.0)
            if (average != null && p95 != null) {
                parts += "first byte avg ${Fmt.decimal(average, 2)} s p95 ${Fmt.decimal(p95, 2)} s (n=${firstBytes.size})"
            }
            parts += "failed requests ${runs.sumOf { it.failures }}"
            if (arm != ProbeArms.K1 && median != null && baselineMedian != null && baselineMedian > 0.0) {
                parts += "time vs K1 ${Fmt.signedPercent(median / baselineMedian - 1.0)}"
            }
            lines += parts.joinToString(" · ")
        }
        return lines
    }

    // ---------------------------------------------------------------------------------------- parallel

    private fun parallelLines(state: SessionState): List<String> {
        val lines = ArrayList<String>()
        lines += "PARALLELISM (bytes received per connection in ${state.config.bucketMs} ms windows; no address or port is printed)"
        val minFull = state.config.thresholds.minFullWidthMs
        for (arm in ProbeArms.candidates) {
            val valid = state.runs.filter { it.step.purpose == StepPurpose.TIMED && it.step.arm == arm && it.valid }
            if (valid.isEmpty()) {
                lines += "${arm.id} · no valid timed run yet"
                continue
            }
            val proven = valid.count { ParallelProof.verdict(it.evidence, minFull) == ParallelVerdict.PROVEN }
            val e = valid.map { it.evidence }
            val protocols = e.flatMap { it.protocols }.toSortedSet().joinToString("/").ifEmpty { "?" }
            lines += listOf(
                arm.id,
                "shown in $proven of ${valid.size} valid runs",
                "connections with bytes min ${e.minOf { it.connectionsWithBytes }} of ${arm.workers}",
                "peak active min ${e.minOf { it.peakActiveConnections }}",
                "at full width min ${Fmt.seconds(e.minOf { it.msAtFullWorkers })} s",
                "calls in flight max ${e.maxOf { it.peakRunningCalls }}",
                "pool max ${e.maxOf { it.peakPoolConnections }}",
                "new sockets max ${e.maxOf { it.newConnections }}",
                "protocol $protocols",
            ).joinToString(" · ")
        }
        return lines
    }

    // ------------------------------------------------------------------------------------ completeness

    private fun completenessLines(state: SessionState): List<String> {
        val lines = ArrayList<String>()
        lines += "COMPLETENESS (verification downloads read stream_id and category_id only; their time is not used)"
        val results = state.reconciliations.values.toList()
        if (results.isEmpty()) {
            lines += "no reconciliation yet: it needs a valid one-reply verification and a walk"
        } else {
            val first = results.first().draft
            lines += "one reply R1 · ${Fmt.count(first.replyRows)} rows · ${Fmt.count(first.replyDistinct)} distinct · " +
                "${Fmt.count(first.replyDuplicateRows)} repeated rows · ${Fmt.count(first.replyRowsWithoutId)} without id · " +
                "${Fmt.count(first.listedCategories)} categories listed · ${Fmt.count(first.unlistedDeclaredRows)} rows under no listed category"
            for (result in results) lines += resultLines(result)
        }
        lines += driftLine(state)
        return lines
    }

    private fun resultLines(result: Reconciliation.Result): List<String> {
        val d = result.draft
        val lines = ArrayList<String>()
        val arithmetic = if (result.balanced) "" else " · ARITHMETIC DOES NOT CLOSE"
        lines += "${d.arm.id} walk against R1 · ${result.completeness.name}$arithmetic"
        lines += "  walk rows ${Fmt.count(d.walkRows)} · distinct ${Fmt.count(d.walkDistinct)} · " +
            "repeated inside a category ${Fmt.count(d.repeatsInsideCategory)} · " +
            "films under several categories ${Fmt.count(d.idsInSeveralCategories)} (extra rows ${Fmt.count(d.extraRowsFromSeveralCategories)}) · " +
            "${Fmt.count(d.walkRowsWithoutId)} without id"
        lines += "  in the reply, not returned ${Fmt.count(result.missing)}: no listed category ${Fmt.count(d.missingOrphans)} · " +
            "category request failed ${Fmt.count(d.missingFailed)} · removed meanwhile ${Fmt.count(result.missingDrift)} · " +
            "unexplained ${Fmt.count(result.missingUnexplained)} · waiting for the drift check ${Fmt.count(result.missingPending)}"
        lines += "  returned, not in the reply ${Fmt.count(result.extra)}: added meanwhile ${Fmt.count(result.extraDrift)} · " +
            "unexplained ${Fmt.count(result.extraUnexplained)} · waiting for the drift check ${Fmt.count(result.extraPending)}"
        lines += "  rows whose own category differs from the one asked ${Fmt.count(d.declaredMismatchRows)} · " +
            "categories whose request failed ${d.failedCategories}"
        if (d.categoryDiffs.isNotEmpty()) {
            lines += "  categories that differ (id: reply rows/walk rows): " +
                d.categoryDiffs.joinToString(", ") { "${it.category}: ${it.replyRows}/${it.walkRows}" }
        }
        return lines
    }

    private fun driftLine(state: SessionState): String {
        val drift = state.drift ?: return "drift check: not done yet (the one reply is downloaded again at the end)"
        return "drift R2 against R1 · distinct ${Fmt.count(drift.before)} → ${Fmt.count(drift.after)} · " +
            "added ${Fmt.count(drift.added)} · removed ${Fmt.count(drift.removed)}"
    }

    // --------------------------------------------------------------------------------------- decision

    private fun decisionLines(state: SessionState): List<String> {
        val t = state.config.thresholds
        val lines = ArrayList<String>()
        lines += "DECISION (fixed in advance: median ≥ ${Fmt.percent(t.medianImprovement)} faster · " +
            "every repeat ≥ ${Fmt.percent(t.repeatImprovement)} faster · no errors · complete · parallelism shown · " +
            "a wider arm only if ≥ ${Fmt.percent(t.widerImprovement)} faster again)"
        val decision = state.decision
        if (decision == null) {
            lines += "not computed yet"
            return lines
        }
        for (verdict in decision.verdicts) {
            lines += "${verdict.arm.id} · ${verdict.status.name}"
            for (criterion in verdict.criteria) {
                val mark = when (criterion.met) {
                    true -> "met"
                    false -> "NOT met"
                    null -> "open"
                }
                val info = if (criterion.blocking) "" else " (info)"
                lines += "  [$mark] ${criterion.name}$info: ${criterion.detail}"
            }
        }
        val preliminary = if (state.status == SessionStatus.PAUSED_PILOT) " (preliminary: pilot only, one run per arm)" else ""
        lines += when (decision.outcome) {
            DecisionOutcome.ADOPT -> "RESULT adopt ${decision.chosen?.id}$preliminary"
            DecisionOutcome.NOT_ADOPTED -> "RESULT do not adopt parallel loading$preliminary"
            DecisionOutcome.INCONCLUSIVE -> "RESULT inconclusive$preliminary"
        }
        for (note in decision.notes) lines += "  note: $note"
        return lines
    }
}
