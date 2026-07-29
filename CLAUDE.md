# Castivio — engineering rules

A premium IPTV player: Android phone, tablet, TV, Google TV, Fire TV first, then
desktop, then the TV platforms, then Apple. The business logic is written once and
rendered many times, so almost none of it is allowed to know what a screen is.

Read this before changing anything. `ARCHITECTURE.md` describes the data layer,
`UI_ARCHITECTURE.md` the presentation layer, `PERFORMANCE.md` the budgets.

## The invariants come first

`UI_ARCHITECTURE.md` §12 lists ten design invariants. They are not preferences. A
change that violates one is answered by changing the invariant deliberately, in its
own commit, or not at all — products decay one reasonable exception at a time.

The mechanical ones block CI:

```sh
./scripts/check-invariants.sh      # seconds; run it before you commit
```

It rejects colour and type literals outside `:core:design`, direction-absolute
layout APIs, a shared component declared twice, and a platform import in a module
that has to compile for every future platform.

The rest are held by the compiler (`Route` is sealed, so a feature cannot invent a
destination; a screen renders a sealed state, so a missing state does not compile)
or by review. When you find a way to mechanise one of the reviewed ones, do it —
a rule that depends on someone remembering it is already broken.

## Working rules

**Mockup before code.** Every screen is designed in `design/mockups/` and approved
before a line of it is written. Deciding the loading, empty, error and success
states while the screen is still a picture costs an hour; discovering the empty
state afterwards costs a rewrite of its state holder.

**Vertical slices, not layers.** Each step runs on a device. A layer that waits for
another layer is a step that cannot be tested.

**A release APK is not a debug APK.** `RELEASE_CHECKLIST.md` lists what has to exist
before one is fit to publish — a licensing backend above all. A release build today
fails closed on purpose; do not "fix" that by giving it a development licensing mode.

**Green before commit.** Build, fix every compiler error, run the tests, and commit
only when they pass. Never commit a red tree "to fix in the next one".

**Production code only.** No placeholders, no `TODO` where behaviour belongs, no
stub that returns an empty list so something compiles. If it is not finished, it is
not committed.

**Every feature ships its tests.** Pure logic is unit-tested without an emulator,
which is why the logic is kept pure.

## Data rules, which the UI must not undo

- **Streaming parsing only.** M3U, XMLTV and JSON are parsed element at a time.
  Memory is O(batch), never O(library) — the target is 400,000 items on a stick with
  1 GB of RAM.
- **Paging everywhere. There is no `getAll()`,** and adding one is a defect. Page 60,
  prefetch 30, at most 300 rows materialised, configured in one place.
- **Counts come from cached values** — an indexed `COUNT` and a denormalised
  `item_count` — never from loading a list to measure it.
- **Search is instant.** No Search button, 120 ms debounce, superseded queries
  cancelled, prefix matching over the FTS index.
- **No blocking work on the UI thread, and no work in composition.** Formatting,
  sorting and filtering happen in the state holder or in SQL.

## Cross-platform rules

Phase 1 is Android only, and the architecture is written so phase 2 is an adapter
exercise rather than a rewrite:

- `:domain`, `:data:parsing`, `:core:navigation`, `:core:common` and
  `:playback:engine-api` are plain Kotlin and must stay that way. The invariant
  script enforces it.
- Every platform dependency sits behind an interface: storage, HTTP, playback,
  capabilities. Room, OkHttp and Media3 are implementations, not assumptions.
- **Do not add SQLDelight, Ktor or a JavaScript core for a platform that is not
  being built yet.** Keep the seams; do not pay for them early.

## Input rules

Castivio is driven by a D-pad first and a thumb second. Every interactive surface
must work with a remote, touch, a keyboard and a screen reader, in both text
directions, at every animation level down to none.

## Layout of the repository

```
core/       common · navigation · design · platform
domain/     contracts, models, policies — pure Kotlin
data/       networking · preferences · activation · entitlement · parsing · database · playlist · epg
playback/   engine-api (contract) · engine-media3 (implementation)
feature/    activation · home · search · player · settings
design/     mockups, rendered from HTML at real device geometry
benchmark/  the performance budgets CI blocks on
```

## Verifying locally

`dl.google.com` is unreachable from the build sandbox, so Gradle cannot resolve the
Android artifacts here. Pure-Kotlin modules can still be compiled and tested
directly with the Kotlin compiler; Compose modules are verified in CI. Prefer
putting logic where it can be tested without an emulator — that constraint has
improved this codebase more than it has cost it.
