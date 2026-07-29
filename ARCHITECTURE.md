# Castivio — Architecture

Castivio is built to run unchanged on every Android-based television device:
Android TV, Google TV, Fire TV / Fire Stick, Chromecast with Google TV, Nvidia
Shield, Xiaomi TV Box, Mecool, Realme, TCL, Sony, Philips, Hisense and generic
Android boxes.

## The rule that makes that possible

**Never branch on brand. Branch on capability.**

```kotlin
// Wrong — unmaintainable within a year.
if (Build.MANUFACTURER == "Xiaomi") { ... }

// Right — describes what the app actually needs to know.
if (capabilities.supports(Codec.HEVC, Hardware)) { ... }
```

A device's badge tells you nothing useful. What matters is what it can decode,
what keys its remote sends, whether Play Services exist, and how much memory it
has. Every one of those is a query, not a brand check.

## Module graph

Dependencies point inward. `:domain` is pure Kotlin and knows nothing about
Android, which keeps the business rules testable on the JVM.

```
:app                    assembly, DI wiring, navigation host

:feature:activation     ─┐
:feature:home            │
:feature:search          ├─ depend on :domain, :core:design, :core:navigation
:feature:player          │
:feature:settings       ─┘

:domain                 models + use cases — pure Kotlin, no Android imports

:data:playlist          M3U + Xtream parsing, channel/VOD repositories
:data:activation        device identity, provider portal
:data:preferences       settings, language, persisted state

:core:design            design system — tokens + reusable components
:core:navigation        route contracts
:core:platform          capability detection behind interfaces
:core:common            result types, dispatchers, logging

:player:engine-api      PlaybackEngine interface — no implementation
:player:engine-media3   Media3 / ExoPlayer implementation
:player:engine-vlc      (future) libVLC fallback for awkward streams
```

## The four interfaces that isolate every platform difference

Each has a real implementation plus a safe fallback, so an unknown device
degrades instead of crashing.

### 1. `PlaybackEngine` — the most important one

IPTV streams are not well-behaved. Some providers serve broken HLS manifests,
unusual audio codecs, or MPEG-TS that Media3 rejects but libVLC plays happily.
Binding the UI directly to ExoPlayer would make that unfixable.

```kotlin
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    val tracks: StateFlow<TrackSelection>   // audio, subtitle, video
    fun open(media: MediaSource)
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    fun selectTrack(track: Track)
    fun setSpeed(speed: Float)
    fun setAspectRatio(mode: AspectMode)
    fun release()
}
```

The player screen talks only to this. Swapping engines — or falling back
per-stream when one fails — touches no UI code.

### 2. `DeviceCapabilities` — what this box can actually do

```kotlin
interface DeviceCapabilities {
    fun supports(codec: Codec, mode: DecodeMode): Boolean
    val hdr: Set<HdrFormat>            // HDR10, HLG, Dolby Vision
    val audioPassthrough: Set<AudioFormat>
    val maxResolution: Resolution
    val memoryClass: MemoryClass       // drives cache size and image quality
    val refreshRateSwitching: Boolean
}
```

Drives real decisions: cache budget, poster resolution, whether to offer 4K,
whether to match display refresh rate to the stream.

### 3. `RemoteProfile` — the keys this remote actually sends

Fire TV remotes have no dedicated guide or number keys; some boxes send
media keys others don't. The focus engine never reads raw key codes.

```kotlin
interface RemoteProfile {
    fun map(keyCode: Int): RemoteAction?   // Up/Down/Select/Back/Guide/Info/PlayPause…
    val hasNumericKeys: Boolean            // if false, offer on-screen channel entry
    val hasDedicatedGuideKey: Boolean      // if false, surface Guide in the UI
}
```

### 4. `PlatformServices` — what this OS provides

```kotlin
interface PlatformServices {
    val voiceSearch: VoiceSearchProvider?  // Assistant, Alexa, or null
    val hasPlayServices: Boolean
    val store: StoreTarget                 // Play, Amazon, sideload — drives update checks
    val leanbackLauncher: Boolean
}
```

`voiceSearch` being nullable is the point: the search screen hides its
microphone rather than showing a button that does nothing.

## Reuse rules

- **Design system** (`:core:design`) is the only source of colour, type, spacing,
  radius, elevation and motion. A feature module that hard-codes a hex value is
  a bug.
- **Focus engine** lives in `:core:design` and is shared by every screen. Row
  memory, focus lift and the rail's open/close rules are implemented once.
- **Navigation** is contract-based: features expose routes, `:app` wires them.
  No feature module imports another feature module.
- **Settings** are a repository in `:data:preferences` exposing `Flow`s, so a
  change (language, theme, aspect ratio) propagates without restarting anything.

## The data layer

Complete as of this branch, and shaped by one rule: **nothing may hold the
catalogue**. Every contract below is written so that the memory-safe path is the
only path a caller can take.

```
:domain            contracts only — CatalogRepository (no getAll), CatalogPager,
                   CatalogWriter, EpgRepository, EpgWriter, FavoritesRepository,
                   ProgressRepository, SourceRepository, RefreshPolicy
:data:parsing      pure Kotlin, benchmarked on every commit — M3U, XMLTV and JSON
                   scanners, the classifier, and the three import engines
:data:database     Room: entities, DAOs, FTS4 search, paging queries, the bulk
                   writers, migrations
:data:networking   OkHttp: conditional fetching, gzip sniffing, stream hashing,
                   the Xtream API client
:data:playlist     CatalogImporter — playlist URL, local file, Xtream
:data:epg          EpgImporter — XMLTV over HTTP
```

### Why the engines are pure Kotlin

The import engines take a `CatalogWriter`/`EpgWriter` and a `Reader`, never a
database or an HTTP client. That is what lets the hottest code in the app be
unit-tested against string fixtures and gated by a per-commit benchmark, on a
runner with no emulator. HTTP and SQLite sit on either side of that boundary and
are tested against a real local server and real SQLite respectively.

### Reads

| Screen needs | Contract | Shape |
|---|---|---|
| A browsable list | `CatalogPager.items` | `Flow<PagingData<MediaItem>>` |
| A show list | `CatalogPager.series` | aggregated by SQL, not in memory |
| Search results | `CatalogRepository.search` | FTS4, bounded by `limit` |
| Now / next | `EpgRepository.nowNext` | visible channel ids only |
| The guide grid | `EpgRepository.window` | visible channels × visible time |

Paging configuration lives in one place next to the budgets — page 60, prefetch
30, at most 300 rows materialised — because those are performance decisions, not
per-screen preferences.

### Writes

`CatalogWriter` takes bounded batches and commits each one, so content appears
while an import is still running. `ImportMode` distinguishes the two kinds of
write, and the distinction is not cosmetic: a `REPLACE` swaps generations and
prunes what the provider no longer lists, while an `APPEND` (a lazily loaded
season) prunes nothing. Treating one as the other would delete the library.

### What a refresh costs

1. `ETag` / `Last-Modified` → a `304` and nothing else happens.
2. No validators → the stream is hashed while parsed; unchanged bytes skip the
   import even though the download could not be skipped.
3. Xtream → nothing is downloaded wholesale in the first place: categories, then
   only what the user opens.

## Device identity and time

Castivio's own licence is bound to a device, not to an account, so two facts have
to be produced before anything else can be decided: which device this is, and
what time it is. Both are contracts in `:domain` with adapters in
`:data:activation`; neither is allowed to be a guess made at a call site.

### The address

`DeviceIdentity` returns six octets written like a set-top box MAC —
`2F:19:EB:20:44:7C` — because that is the string a user reads off a television
and sends to a provider by hand. It is **derived, never generated**:

```
material  := "castivio/device-identity/v1" ‖ "\n" ‖ seed.material
digest    := SHA-256(UTF-8(material))
octets    := digest[0..5], with octet 0 forced locally-administered and unicast
```

The seed is `Settings.Secure.ANDROID_ID`, normalised and left-padded to sixteen
digits, prefixed `os:`. When that value is missing or is one of the known
degenerate constants whole production runs shipped with, a random UUID is minted
and prefixed `install:` instead — and the resulting `IdentityProvenance` is
carried to the licence server, because an address that dies with the app's data
deserves different treatment from one that does not.

No hardware address is read and no permission is declared. Android has not
allowed the former since Marshmallow, and it would be the wrong input anyway:
Wi-Fi addresses are randomised per network and a device on Ethernet has none.
`Build.MANUFACTURER` and `Build.MODEL` are deliberately *not* mixed in — they are
not secret, they are less stable than they look, and using them would paper over
the shared-`ANDROID_ID` collision instead of detecting it.

**The version is the contract.** `DeviceIdentityV1` is frozen; a new derivation is
a new object beside it with its own label and its own entry in
`DeviceIdentityAlgorithm`. The seed is stored verbatim on first use so every
future version derives from the same material, the address is stored per version
(`mac.v1`, `mac.v2`, …), and `DeviceIdentity.legacy()` hands the older addresses
to the licence server so an entitlement can be moved rather than stranded. An
edit that changes a byte of v1's output is not a refactor; it is a mass
revocation, and the pinned test vectors are there to make that impossible by
accident.

### Two addresses, never one

Castivio's identity and a protocol's identity are different things, and the moment
they are allowed to be the same value one of them starts dictating the other.

- **Device / Licence MAC** — what `DeviceIdentity` returns. Locally administered,
  derived by `DeviceIdentityV1`, and the only identity that Castivio's own trial,
  annual licence, lifetime licence, app entitlement and recovery path are bound to.
  It answers to nothing outside this codebase.
- **Provider / Portal MAC** — *not built, and not to be built until a slice needs
  it.* Some Stalker and Ministra panels validate that the address they are given
  begins with a set-top-box prefix such as `00:1A:79` and reject anything else.
  Where that turns out to matter, the answer is a **second, separate** address
  derived deterministically from the *same* stored seed under its own label — never
  a change to the licence address, and never a `DeviceIdentity` v2.

The rule, stated so it cannot be argued away later:

> Castivio's licence identity does not take requirements from Stalker, Ministra or
> any other provider protocol. A protocol that needs a particular shape of address
> gets its own address.

A v2 of `DeviceIdentity` means "we changed how this device's licence identity is
derived, and every entitlement in the field has to be migrated". Adding a
provider-facing address means nothing of the sort, and calling it a version bump
would drag a migration through the licence server for a problem the licence server
does not have.

### The clock

The device clock is user-settable, which makes it unfit to decide when a trial
ends. `TrustedTime` answers with both a number and the reason to believe it, and
`MonotonicClock` assembles that from three signals: the wall clock, elapsed
realtime since boot, and the kernel's boot identifier.

- An **anchor** from Castivio's own licence host, projected forward with elapsed
  realtime, ignores the wall clock entirely — nothing on the device can set
  elapsed realtime. Anchors are never harvested from a provider's server: a
  provider URL is typed in by the user, and an anchor may move time backwards.
- Otherwise the wall clock is reported, **floored at the furthest instant this
  device has ever observed**. Winding the date back buys nothing; winding it
  forward is a self-inflicted wound.
- A trusted anchor is the only thing allowed to lower that mark, which is what
  repairs a device whose fast clock ended its own trial.

The mark lives in two places and both obey the same asymmetry:
`ClockState.highWaterMarkMs` for the clock, `EntitlementRecord.maxObservedTimeMs`
for the licence. `EntitlementRecord.observing(reading)` is what keeps them in
step — a device reading raises it, a `NETWORK` reading replaces it — and
`EntitlementPolicy.evaluate(record, reading, config)` returns the repaired record
alongside the state so the caller stores the correction rather than re-deriving it.
Without that second half, a fast clock would end a paid subscription permanently:
the clock would recover and the record would not.

`MonotonicClock` holds no state: it loads, computes purely, and writes back only
on change, so every awkward case — reboot, process death, dead coin cell, a
`Date` header from a broken proxy — is a unit test rather than something one
hopes about a television.

## Testing posture

`:domain` and the parsers in `:data:parsing` are pure Kotlin and unit-tested on
the JVM — the parts most likely to break on a provider's malformed playlist are
also the fastest to test. Capability interfaces are trivially faked, so screens
can be tested against a "cheap box" profile and a "Shield" profile without
owning either device.
