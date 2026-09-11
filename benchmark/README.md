# Performance measurement

Performance is measured, not asserted. This directory holds the machinery.

## Three tiers, and why there are three

The metrics you asked to track do not all have the same measurability. Pretending
they do would produce a wall of green checkmarks that catches nothing — so they
are split by where a number can honestly be trusted.

| Metric | Tier | Gated? |
|---|---|---|
| Playlist parsing time | JVM, every commit | **Yes — fails the build** |
| XMLTV parsing time | JVM, every commit | **Yes — fails the build** |
| Parse memory retention | JVM, every commit | **Yes — fails the build** |
| Import throughput (parse → classify → row) | JVM, every commit | **Yes — fails the build** |
| Import memory retention | JVM, every commit | **Yes — fails the build** |
| Guide import throughput | JVM, every commit | **Yes — fails the build** |
| Guide import retention | JVM, every commit | **Yes — fails the build** |
| Xtream JSON throughput | JVM, every commit | **Yes — fails the build** |
| Xtream JSON retention | JVM, every commit | **Yes — fails the build** |
| Search query correctness | Robolectric, every commit | **Yes — fails the build** |
| Search latency (query cost) | Device / Robolectric | Tracked, gate pending — needs real SQLite, so it cannot live in the JVM tier |
| Database indexing time | Device / Robolectric | Tracked, gate pending |
| Cold startup time | Device | Gate on fixed hardware only |
| Warm startup time | Device | Gate on fixed hardware only |
| Time to first frame | Device | Gate on fixed hardware only |
| Frame drops · Jank % | Device | Gate on fixed hardware only |
| Memory usage (resident) | Device | Gate on fixed hardware only |
| Channel switch latency | In-app telemetry | Reported, cannot be CI-gated |
| ANR count | Production vitals | Reported |

### Tier 1 — JVM budgets, every commit, blocking

`:benchmark:jvm`, run by `.github/workflows/performance.yml`. No emulator, a
few seconds, and it fails the build on breach.

This tier exists because the parsers are deliberately pure Kotlin. That single
architectural decision is what makes the hottest code in the app measurable on
every commit.

**What it catches:** algorithmic regressions. A `Regex` added per playlist line,
a `SimpleDateFormat` per programme, or a parser that starts accumulating a list
— all move the numbers by an order of magnitude and trip the gate immediately.

Baselines as measured on a GitHub-hosted runner when the gates were written:

| Gate | Baseline | Budget |
|---|---|---|
| M3U parse | 2,445,614 entries/sec | 40,000 |
| Full import — parse → classify → row → batch | 492,282 entries/sec | 50,000 |
| XMLTV parse | 175,759 programmes/sec | 25,000 |
| XMLTV timestamps | 20,171,675 conversions/sec | 1,000,000 |
| Parse retention, 300,000 entries | 0 KB | 24 MB |
| Import retention, 299,400 items | 175 KB | 24 MB |
| Guide import | 127,665 programmes/sec | 20,000 |
| Guide retention, 200,000 programmes | 1 KB | 24 MB |
| Xtream stream rows | 395,430 rows/sec | 20,000 |
| Xtream retention, 200,000 rows | 0 KB | 24 MB |

The retention rows are the architecture working: memory after streaming 300,000
items is a rounding error, because the engine holds one batch and a group index
and nothing else.

One caveat on the throughput rows, stated rather than hidden: the JIT can still
compute a parsed field's length without materialising the string, so the
absolute figures flatter the parser somewhat even with [Sink] consuming every
field. That does not affect what the gate is for — a `Regex` per line or a list
being accumulated changes these numbers by an order of magnitude either way.

**What it does not catch:** a genuine 15% slowdown. Shared CI runners vary 2–3x
between runs, so a tight budget would flake daily and be disabled within a week.
Budgets here are set to catch structure, not drift. The most valuable test in
this tier is not a timing at all — it is
`m3u parsing does not retain the catalogue`, the executable form of "the
catalogue is never in memory".

### Tier 2 — device benchmarks, on real hardware

Startup, frame timing, jank and memory need a device. They can technically run
on a CI emulator, but on a shared runner the variance is larger than the
regressions worth catching, so **a hard gate there would be theatre**.

The honest arrangement:

- **Run on a fixed device** — a self-hosted runner with a low-end box attached
  (a Fire TV Stick is ideal: it is the hardware that matters and the one users
  complain about). On fixed hardware the variance collapses and budgets in
  `PerformanceBudgets` become enforceable.
- **Until that runner exists,** these run on demand and are *tracked*, so
  regressions are visible in the trend even before they can block a merge.

Measured with `androidx.benchmark.macro` (`StartupTimingMetric`,
`FrameTimingMetric`, `MemoryUsageMetric`), plus a Baseline Profile — worth
20–30% of cold start on its own, and the cheapest win available.

**This tier now exists.** `:benchmark:macro` was added in Phase B; see *Running the
device tier* below. The Baseline Profile has not been added and is deliberately out of
scope — it is an optimisation, and Phase B measures rather than optimises.

### Tier 3 — telemetry, from real use

Two of the requested metrics genuinely cannot be measured in CI:

- **Channel switch latency** depends on a live provider's edge. A synthetic
  measurement would tell you about the test fixture, not the product. The app
  measures it in place and splits *our* overhead from *provider* time — that
  split is what Diagnostics reports, and it is what keeps a slow provider from
  being blamed on Castivio.
- **ANR count** only exists where real users are. Tracked from Play Console
  vitals; the budget is zero.

## Budgets

All numbers live in one place: `PerformanceBudgets.kt`. Every Tier 1 value is
asserted by a test. Tier 2 values are declared alongside them so the device
suite has nothing of its own to drift from.

Failure messages state the measurement, the budget and the likely cause. A red
build at 2am should say what to look at, not just that a number moved.

## Running locally

```bash
./gradlew :benchmark:jvm:test           # Tier 1 — seconds, no device
./gradlew :data:database:testDebugUnitTest   # Room queries against real SQLite
./gradlew :data:networking:testDebugUnitTest # HTTP against a local server
```

Measurements are printed as `[budget] name: value` and collected into the CI job
summary on every run, so the trend is visible without opening a report.

---

## Running the device tier

`:benchmark:macro` is a `com.android.test` module: it builds its own APK, installs
beside `:app`, drives it from outside and reads the platform's own counters. Nothing in
it can be linked into a shipped build.

> **Not yet verified to build.** It is committed as source and has never been through a
> successful `assembleBenchmark`: the CI runner has no KVM and no device, and the one
> thing a `com.android.test` module's configuration cannot be checked by is compiling it
> somewhere it can never run. Expect to fix a Gradle configuration error the first time
> you build it — the error will be in front of you, which is the entire reason it is not
> gated in CI, where it was truncated out of the log on every attempt.

```sh
# Everything: startup, section opening, scrolling.
./gradlew :benchmark:macro:connectedBenchmarkAndroidTest

# Startup alone.
./gradlew :benchmark:macro:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.castivio.benchmark.macro.StartupBenchmark
```

Results print to the console and are written as JSON under
`benchmark/macro/build/outputs/connected_android_test_additional_output/`.

**Do not read absolute numbers off an emulator.** An emulator's timings are the host
machine's, and the device this product is judged on is a 2 GB stick. The module runs
there and the numbers are real; they are real about the wrong hardware.

### Why a `benchmark` build type exists

Neither existing type can be measured. **Debug** carries the debuggable flag, which turns
off ART's optimising compiler and inflates every timing by a device-dependent amount.
**Release** fails closed on purpose — `Licensing.Production` is bound with no
`EntitlementSource` (see `RELEASE_CHECKLIST.md`), so a startup benchmark would be timing
the licence screen saying no.

So the `benchmark` type takes release's runtime characteristics and debug's licensing:
`matchingFallbacks = ["debug"]` makes every library module compile its debug variant,
which puts `BuildConfig.DEBUG == true` in front of `EntitlementModule.licensing` and
gives the build a working local trial. The app's own `BuildConfig.DEBUG` stays false, so
StrictMode and the crash sheet stay out of the measurement.
`<profileable android:shell="true"/>` in `app/src/benchmark/AndroidManifest.xml` is what
lets the platform sample a non-debuggable process.

### The trace sections

Five boundaries, named once in `CastivioTrace` and read from there by both the app and
the benchmark — so a rename cannot leave a benchmark asking for a section nobody emits
and reporting zero occurrences as though that were a measurement.

| section | what it answers |
|---|---|
| `Castivio.Fetch` | a first open of a section, end to end |
| `Castivio.Api.<action>` | the `1 + N` round trips, timed and separated by action |
| `Castivio.Commit` | SQLite's share, which the JVM tier deliberately stubs out |
| `Castivio.FirstPage` | when the pager answered |
| `Castivio.FirstContent` | when a viewer first had something to act on |

`Trace.beginSection` is a flag check when nothing is recording, which is every run that
is not a benchmark.

## Counting a real provider's requests

Opening a section costs `1 + N` sequential round trips, where `N` is how many categories
the provider has. **`N` cannot be learned from this repository**: it is a property of one
subscription, not of the code, and no fixture can stand in for it.

`CallMetrics` counts it. It is an OkHttp `EventListener` — told what happened, given no
way to change it, which is why it is safe to add during a phase forbidden to optimise. No
URL, host, username or password reaches a counter or a log line; `CallMetricsTest`
asserts that, and asserts the labelling in both directions.

```sh
adb logcat -c
adb logcat -s CastivioNet:I
```

Then on the device: add your provider, open **Live** for the first time, and wait.

```
── import LIVE ──
total: <n> call(s) over <ms> ms of wall clock
time in calls: <ms> ms (sum) against <ms> ms (span)
overlap factor: 1.00 — 1.00 is fully sequential
  get_live_streams: <n> calls, 0 failed, mean <ms> ms, slowest <ms> ms, <n> KB
  get_live_categories: 1 calls, 0 failed, mean <ms> ms, slowest <ms> ms, <n> KB
```

`get_live_categories` is the `1`; `get_live_streams` is the `N`. The **overlap factor**
is the sum of the call durations over the wall clock they occupy: at 1.00 the requests
were strictly serial, above it they overlapped. Repeat for Movies and Series — the counts
differ per kind, sometimes by an order of magnitude.

**Credentials stay on your device.** Not in this repository, not in a workflow, not in a
message.

## What is deliberately not measured

- **First image.** There is no image loading in Castivio: `MediaCard` draws a generated
  placeholder and `artwork_url` is stored but never read. The metric has no subject, so
  no number is reported for it rather than a zero that would look like success.
- **A single database query, in isolation.** Room is measured through `Castivio.Commit`
  and `Castivio.FirstPage`, which are what a user waits for. A microbenchmark of one
  `SELECT` would be a number about SQLite rather than about the app.
