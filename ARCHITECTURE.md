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

## Testing posture

`:domain` and the parsers in `:data:playlist` are pure Kotlin and unit-tested on
the JVM — the parts most likely to break on a provider's malformed playlist are
also the fastest to test. Capability interfaces are trivially faked, so screens
can be tested against a "cheap box" profile and a "Shield" profile without
owning either device.
