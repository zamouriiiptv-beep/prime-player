# Performance measurement

Performance is measured, not asserted. This directory holds the machinery.

## Two tiers, and why there are two

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
| Search latency (query cost) | JVM, every commit | **Yes** — once FTS lands |
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
./gradlew :benchmark:jvm:test          # Tier 1 — seconds, no device
```

Measurements are printed as `[budget] name: value` and collected into the CI job
summary on every run, so the trend is visible without opening a report.
