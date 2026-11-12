# Castivio — Performance

Performance is the product. A player that looks beautiful and zaps in 1.5s
loses to an ugly one that zaps in 300ms. Where the two conflict, speed wins —
including against effects in this project's own design system.

## The number that dictates the architecture

A large provider is 100k live channels, 200k movies and 100k series: **400,000
items, roughly 100 MB of M3U text**.

| Approach | Retained heap | Result on a Fire Stick (~128 MB heap) |
|---|---|---|
| `List<Channel>` in memory | 120–200 MB | **OOM crash** |
| SQLite + Paging, render only visible | ~4–8 MB | flat, regardless of library size |

So the rule the whole app is built on:

> **The catalogue is never in memory. It lives in SQLite and is paged.**

Memory use must be *independent of library size*. A 400k library and a 400 item
library have the same resident footprint, because only ~30 rows are ever
materialised — the visible window plus a small prefetch margin.

## Budgets

These are targets to measure against, not aspirations. Numbers are for a
low-end box (2 GB RAM, ~128 MB heap), not a Shield.

| Path | Budget |
|---|---|
| Cold start → first frame | < 1.2 s |
| Cached playlist → Home usable | < 400 ms |
| First channels visible on a fresh 400k M3U | < 1.5 s (progressive) |
| Channel zap, warm neighbour | < 250 ms |
| Channel zap, cold | provider-bound; < 200 ms of *our* overhead |
| Search keystroke → results | < 50 ms |
| EPG now/next for visible rows | < 100 ms |
| Frame time while scrolling | < 16.6 ms (60 fps), no dropped frames |

## Playlist loading

### Never hold the file, never hold the list

Parse is a **stream**: `okio`/`BufferedReader` line sequence in, batched
`INSERT` out. At no point does the whole file — or the whole parsed list —
exist as objects. Bulk insert uses prepared `SupportSQLiteStatement` rather
than the ORM path, inside transactions of ~1,000 rows.

During import only: `PRAGMA synchronous = OFF` (a 3–5× speedup on cheap flash
storage) and an 8 MiB page cache in place of SQLite's ~2 MB default
(`ImportPageCache`), both put back afterwards. The journal stays WAL throughout:
in a rollback journal the writer's open transaction holds every reader off, and
first content would wait for the whole import.

The page cache is for the two indices written in no order — the primary key, whose
ids are hashes, and the category index when a provider's list does not keep a
category's rows together. On a desktop SQLite with this writer's table, indices
and statement it took 8–21% off the insert time at 54k rows and 18% at 400k;
32 MiB was scarcely better than 8. A device's share is smaller (its rows also pay
for the JNI bindings) and has not been measured: the load strip's "writer rows"
is where it will show.

### One pass, one batch

Parsing, classification and row construction all happen in the same pass
(`CatalogImportEngine`). An M3U file does not say what its entries *are* —
providers mix live channels, films, series episodes and radio into one flat list
— so each entry is classified as it goes, from the URL path, a season/episode
marker, the group title and the duration, in that order of authority. No second
pass over the file, and no intermediate list between the two.

Series are stored as their episodes with a shared `seriesId`; the Series screen
is a `GROUP BY` over that column. Building a `Map<String, List<Episode>>` during
import would put the entire series catalogue on the heap for no gain.

Classification adds real work per entry, so it is gated like everything else:
`IMPORT_ENTRIES_PER_SECOND_MIN`, measured end-to-end with the database stubbed.

### Progressive availability beats total speed

400k rows takes 10–25s to insert on a weak box, and no trick removes that.
What we remove is the *wait*: rows are committed per group, and the UI observes
the table. The first group appears in well under a second and the rest fills in
behind it. **Time-to-first-content is the metric users feel**, not
time-to-complete.

### A refresh is not a first load

A first load has a viewer waiting for the first category, so a section's one
reply is parsed as it arrives. A **refresh** — the section already holds rows for
that source — has rows on the screen and a viewer who wants it to *end*, and it
is not the same job:

- **The reply is taken to a file first** (`ReplySpool`, in the application's cache,
  deleted when read), then parsed from the file. Read as it arrives, the
  connection stays open for as long as the database writer needs — the parser
  reads nothing while it waits for the writer — and on a phone whose writer took
  about a millisecond a row that was minutes, against ten seconds for the reply to
  arrive. A weak network broke it at 40,000 of 54,000 rows and the import went on
  to ask for 833 categories one at a time: 619 s, against 74 s when the same
  update was not cut. From a file the reply is over when the connection has
  delivered it, and a file does not break. The price is that writing begins when
  the download ends instead of beside it, so a refresh that would not have been
  cut takes about the download's time longer. Skipped below 256 MiB free, and for
  the radio.
- **The screens are told every 10 s, and at the end**, instead of every second
  (`RoomCatalogWriter.tellRarely`). A refresh replaces rows the screens already
  show, and each telling makes every one of them read the tables again — on a phone
  with a large catalogue, a large share of the processor during the load. The
  first rows and the first counted category are still told at once.

Neither applies to a first load, which is unchanged. The load strip says which was
used: its `reply:` line gives the one reply's outcome and, when it was not used,
the cause, the rows it had delivered and the seconds it had run; its `database:`
line gives how often the screens were told.

### Do the work once

- `HEAD` with `If-None-Match` / `If-Modified-Since`; a `304` skips everything.
- No validators? Hash the stream while parsing and compare to the last import.
- After first run, launch is an `open()` on an existing database — instant.

### Xtream: don't download the catalogue at all

The Xtream API is category-addressable, and this is the single biggest win
available:

```
get_live_categories          → ~100s of rows, instant
get_live_streams&category_id → only what the user actually opened
```

Categories load immediately; a category's streams load when the user enters it.
A 100k-channel Xtream provider becomes usable in well under a second because we
never fetch 100k rows. M3U has no such API — hence the streaming path above.

## Search

`LIKE '%q%'` over 400k rows is a full table scan and is never used.

**FTS4** virtual table (FTS4, not FTS5 — FTS5 is not guaranteed on the SQLite
shipped with API 23, the floor) with prefix queries (`nova*`). Sub-30 ms on 400k rows.

The query pipeline is `debounce(120ms)` → `flatMapLatest` → IO dispatcher, so a
fast typist issues one query, not eight, and every superseded query is
cancelled rather than completed and discarded.

## EPG

XMLTV guides reach 100 MB and millions of programmes. Same discipline:

- `XmlPullParser` streaming, batched insert. Never DOM.
- Indices on `(channelId, stopMs)` and `(startMs)`.
- Query only the visible window: visible channel ids × visible time range.
- Retention: drop programmes older than 24 h, cap the future at ~7 days.
- For now/next, prefer Xtream's `get_short_epg` per channel — kilobytes instead
  of a full guide download.

## Channel switching — the headline

Zapping is what a live-TV user does most, and it's where this player has to win.

1. **Never recreate the player.** One `ExoPlayer` instance for the session;
   `setMediaItem()` + `prepare()`. Construction alone costs 50–150 ms.
2. **Tune `LoadControl` for latency, not smoothness** (see
   `PlaybackTuning` in `:playback:engine-api`): start playback at ~500 ms
   buffered instead of ExoPlayer's default 2,500 ms, and set
   `prioritizeTimeOverSizeThresholds(true)` — essential for live.
3. **Pre-warm the neighbours.** On `MemoryClass.HIGH`, a second player is kept
   prepared on the next channel in the current list, so Up/Down is a surface
   swap. Costs ~20–40 MB, which is why it is capability-gated and off on
   low-memory devices.
4. **Reuse connections.** One OkHttp client with a warm pool: consecutive
   channels usually share a host, so TLS handshake and DNS are already paid.
5. **Cache resolved URLs** for the session so redirects aren't re-walked.
6. **Never wait on LibVLC with the main thread.** LibVLC 3 lets go of a source
   synchronously: stopping or releasing a player joins the thread that reads the
   stream, and for a stream that never played (a server that answered and said
   nothing, a name still being resolved) that thread ends when its read gives up
   — seconds. The backup engine's `release()` hands the whole teardown to a
   thread of its own (`BoundedTeardown`) and waits for it at most
   `TEARDOWN_GRACE_MS` (200 ms): a quick teardown is finished before the next
   channel asks for a decoder, as before, and a slow one is not a frozen screen.

**Honest limit:** if the provider's edge needs 800 ms to first byte, no client
trick fixes it. Our budget is *our* overhead — under 200 ms — and Diagnostics
reports the split so a slow provider is visibly the provider.

## Resilience

- Errors are classified (network / format / decoder / auth), not lumped
  together. Only network errors retry.
- Retry is exponential with a cap, preserving position; live re-prepares at the
  live edge rather than resuming stale.
- **Stream fallback**: when a provider exposes alternates (Xtream `ts` vs
  `m3u8`), a format failure tries the other before surfacing an error.
- **Decoder fallback**: hardware first, always; `setEnableDecoderFallback(true)`
  drops to software only when hardware init actually fails. Diagnostics shows
  which decoder is live.

## Rendering at 60 fps

- `LazyRow`/`LazyColumn` with stable `key` and `contentType` so recycling works
  and item identity survives paging.
- Models are immutable `data class`es with `val`s; UI state is hoisted, and
  `derivedStateOf` guards anything that would otherwise recompose per frame.
- Images decode at `DeviceCapabilities.posterWidthPx` (240 px on a low-end box).
  Decoding a 4K JPEG into a 120 dp poster is the classic TV-app frame killer.
- Disk cache budget comes from `DeviceCapabilities.recommendedCacheBytes`.
- **A logo is waited for, not waited to fail.** A logo that has not come in 1.5 s
  is drawn as the no-logo tile while its request goes on, and the picture replaces
  the tile if it comes; a logo request is given up at 6 s, whole, and not at the
  client's 12 s to connect and 20 s between packets; and an address that failed is
  not asked for again for ten minutes (`ArtworkPolicy`, `ArtworkFailures`). The tile
  used to wait for the request to *fail*, which for a host that does not answer was
  an empty slot for twelve to twenty seconds, again each time the row came back.

### The design system pays this tax too

The animated aurora backdrop is the one thing in the application that is always
moving, so what it costs is paid on every screen. On a weak GPU that competes
directly with scrolling, so it is **capability-gated**: full animation on
capable devices, a static gradient on `MemoryClass.LOW`. The gate is the *motion
level* (`MotionLevel.backdropAnimates`): the device chooses the level's starting
value, and the Settings → Motion choice that follows is honoured in both
directions — Reduced and Disabled hold the backdrop still, on any device.

What it costs while it *does* move is held to four rules (`Backdrop.kt`):

- **The clock is read in draw, never in composition.** A transition read in
  composition recomposed its reader every frame; read in draw, a tick invalidates
  the drawing and nothing else.
- **The drawing is its own layer, and the content on top of it is another.** A
  tick invalidates the nearest layer, which with none in between is the whole
  window — every text, image and list row re-recorded to move a faint line.
- **Nothing is rebuilt that has not changed.** Brushes and glow geometry are made
  when the rectangle or the colours change; one `Path` serves all seventeen
  waves; the motes are a table.
- **Thirty moves a second, not sixty, and thirty-two segments a wave, not
  sixty-four.** `BackdropMotionTest` holds both to the look: one move shifts no
  wave by more than a pixel, and the polyline stays within a pixel of the curve.

This is the rule working as intended: the project's own signature effect yields
to frame rate.

## Startup

- `CastivioApp.onCreate` does nothing. No eager singletons, no DB open, no
  network. Everything is lazy and off the main thread.
- **Baseline Profile** ships with the app — reliably 20–30% off cold start, and
  the cheapest win available.
- Splash capability checks are framework queries (codec list, display), costing
  single-digit milliseconds — they are not the reason the splash exists.

## Keeping it honest

Budgets that aren't measured are decoration, so they are enforced in CI. See
`benchmark/README.md` for the full picture; the short version is three tiers,
split by where a number can honestly be trusted:

- **Every commit, blocking** — parsing throughput and, most importantly, parse
  *memory retention*. `:benchmark:jvm` runs in seconds with no emulator because
  the parsers are pure Kotlin, and it fails the build on breach.
- **On real hardware** — startup, frame timing, jank, memory. These need a
  device; a hard gate on a shared CI emulator would measure runner noise rather
  than the app, so they gate on a fixed low-end box and are tracked until one
  is attached.
- **From real use** — channel-switch latency (provider-dependent) and ANRs.
  Reported through Diagnostics and Play vitals, with the app splitting our
  overhead from provider time so a slow provider is visibly the provider.

All numbers live in `PerformanceBudgets.kt`. Additionally:

- Macrobenchmark for cold start and scroll jank, run on the lowest-end device
  in the matrix, not the fastest.
- Import and search timings recorded behind Diagnostics, so real-world
  regressions surface from real libraries rather than synthetic ones.
- StrictMode in debug: any disk or network access on the main thread fails
  loudly during development rather than quietly shipping.
