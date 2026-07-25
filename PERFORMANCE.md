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

During import only: `PRAGMA synchronous = OFF`, `journal_mode = MEMORY`,
restored afterwards. That alone is a 3–5× speedup on cheap flash storage.

### Progressive availability beats total speed

400k rows takes 10–25s to insert on a weak box, and no trick removes that.
What we remove is the *wait*: rows are committed per group, and the UI observes
the table. The first group appears in well under a second and the rest fills in
behind it. **Time-to-first-content is the metric users feel**, not
time-to-complete.

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
shipped with API 21) with prefix queries (`nova*`). Sub-30 ms on 400k rows.

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

### The design system pays this tax too

The animated aurora backdrop redraws every frame. On a weak GPU that competes
directly with scrolling, so it is **capability-gated**: full animation on
capable devices, a static gradient on `MemoryClass.LOW`. The Settings toggle
exists for users who want it back — but the default is chosen by the device,
not by taste.

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

Budgets that aren't measured are decoration:

- Macrobenchmark for cold start and scroll jank, run on the lowest-end device
  in the matrix, not the fastest.
- Import and search timings recorded behind Diagnostics, so real-world
  regressions surface from real libraries rather than synthetic ones.
- StrictMode in debug: any disk or network access on the main thread fails
  loudly during development rather than quietly shipping.
