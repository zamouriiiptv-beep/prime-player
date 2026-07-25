# Castivio — UI/UX Architecture

Specification for review. No implementation code is written against this until it
is approved.

The data layer is complete and platform-independent by construction. This document
describes the layer above it: what the user sees, how they move through it, and how
one product stays coherent across a television remote, a phone, and a mouse.

---

## 1. The cross-platform position

### What is already portable

The layer built so far was written to be portable before it was written to be
Android. Concretely:

| Component | Language | Portable today? |
|---|---|---|
| Domain contracts, models, policies | Pure Kotlin | Yes |
| M3U / XMLTV / JSON parsers | Pure Kotlin, no platform APIs | Yes |
| Catalogue, guide and Xtream import engines | Pure Kotlin | Yes |
| Classifier, id derivation, search-query builder | Pure Kotlin | Yes |
| Performance budgets and their tests | Pure Kotlin | Yes |
| Persistence (Room) | Android/JVM | Needs an adapter |
| HTTP (OkHttp) | JVM | Needs an adapter |
| Playback (Media3) | Android | Per platform by definition |

The engines take a `CatalogWriter` and a `Reader`; they have never seen a database
or an HTTP client. That is why the port is an adapter exercise rather than a
rewrite — and it is the reason the split was made that way.

### The target boundary

```
┌──────────────────────────────────────────────────────────────┐
│ Presentation — per platform                                  │
│ Compose (Android/TV/Desktop) · SwiftUI (iOS/tvOS) · TS (web)  │
├──────────────────────────────────────────────────────────────┤
│ Presentation logic — shared: state holders, formatting,       │
│ focus rules, navigation intents                               │
├──────────────────────────────────────────────────────────────┤
│ Domain — shared, pure: contracts, models, policies            │
├──────────────────────────────────────────────────────────────┤
│ Data — shared logic, per-platform adapters:                   │
│ parsers & engines (shared) · storage · http · playback        │
└──────────────────────────────────────────────────────────────┘
```

**The rule: a feature is designed once and rendered many times.** Any behaviour
that could be described without naming a widget belongs above the line, in shared
code — including which item is focused, what a screen does on Back, and what a
search shows after three characters.

### Platform plan, honestly costed

| Platform | Shell | Core delivery | Effort beyond presentation |
|---|---|---|---|
| Android phone/tablet | Compose | Native | None |
| Android TV / Google TV | Compose (TV) | Native | None |
| Fire TV | Compose (TV) | Native | Store target, remote profile |
| Windows / macOS / Linux | Compose Multiplatform desktop | Kotlin/JVM | Storage + HTTP adapters, VLC/libmpv or ExoPlayer-desktop |
| iPhone / iPad / Apple TV | SwiftUI | Kotlin/Native | Storage (SQLDelight), HTTP (Ktor), AVPlayer |
| Samsung Tizen / LG webOS | TypeScript + React | Kotlin/JS or WASM | Storage (IndexedDB), HTTP (fetch), platform AV pipeline |

Two changes make all of it possible and should be planned before the second
platform, not after:

1. **Storage:** Room → SQLDelight (or Room KMP). The DAO surface is small and
   already expressed as bounded queries, so this is mechanical.
2. **HTTP:** OkHttp → Ktor with an OkHttp engine on JVM. `HttpStreamSource`'s
   contract — conditional request, gzip sniffing, stream hashing — is unchanged.

Web is the only genuinely different target: no SQLite, no file streams, and a
different memory model. The parsers already stream, which is what makes it viable
at all, but it should be scoped as its own project rather than assumed.

---

## 2. Navigation map

```
Splash
 └─ Activation (first run only)
     ├─ Activate by code        (portal / MAC)
     ├─ Xtream login
     ├─ Playlist URL
     └─ Local file
          ↓ (import runs, catalogue becomes browsable)
Home ────────────────────────────────────────────────────────────
 ├─ Live TV ──┬─ Categories ─ Channel list ─ Player
 │            └─ Guide (EPG grid) ─ Programme ─ Player
 ├─ Movies ───┬─ Categories ─ Grid ─ Movie detail ─ Player
 │            └─ Search results
 ├─ Series ───┬─ Categories ─ Grid ─ Series detail ─ Seasons ─ Episode ─ Player
 ├─ Radio ────── Categories ─ Station list ─ Player (audio mode)
 ├─ Favorites ── Mixed list ─ (destination by kind)
 ├─ Continue watching ── Resume ─ Player
 ├─ History ──── Mixed list
 ├─ Search ───── Instant results, grouped by kind
 └─ Settings ──┬─ Playback (internal/external, buffering, aspect)
               ├─ Language & region
               ├─ Appearance (theme, motion, backdrop)
               ├─ Providers (add, switch, refresh, remove)
               ├─ Guide (source, refresh interval, retention)
               ├─ Parental control
               ├─ Diagnostics (import timings, decoder, cache)
               └─ About
```

**Depth budget: three levels from Home to playing.** Live is two
(Home → Live → play). Movies is three (Home → Movies → detail → play), and detail
is skippable with a direct Play action on the poster.

### Route contract

Routes are declared per feature and wired by the shell, so no feature imports
another. A route is a data class, not a string, so a compile error is what happens
when a screen's arguments change — not a crash on a TV.

```
Route.Home
Route.Section(kind)                 // LIVE | MOVIE | SERIES | RADIO
Route.Category(kind, groupId)
Route.Detail(mediaId)
Route.Series(seriesId)
Route.Player(mediaId, startPositionMs?, timeshiftMs?)
Route.Search(initialQuery?)
Route.Guide(channelId?)
Route.Settings(section?)
Route.Activation(method?)
```

---

## 3. Screens

Every screen below is specified with: its job, what it shows while empty, and what
holds focus when it opens.

### 3.1 Splash

Runs the real startup work — capability detection, database open, deciding whether
a refresh is due — and hands over. Never a fixed-duration animation: it ends when
the work ends, which on a warm start is under 400 ms.

Shows a determinate step only if startup exceeds 1.5 s (a cold first import).

### 3.2 Activation

Four methods, weighted by how they are actually used: **activation code first**
(the primary path), then Xtream, playlist URL, local file. Already designed and
approved; unchanged here except that the import progress it shows now comes from
real `ImportProgress` events — counts, not a fake percentage.

Initial focus: the code field. On TV, the on-screen keyboard opens with it.

### 3.3 Home

Rows, in this order, with the first row focused:

1. **Spotlight** — live-first: what is on now on a favourite or recent channel.
2. **Continue watching** — only when non-empty.
3. **Live TV** — recent and favourite channels.
4. **Movies** — recently added.
5. **Series** — recently added.
6. **Radio** — only when the provider has radio.

Each section header carries its **cached count** ("Live TV · 12,480"), read from
the counts the data layer already maintains — an indexed `COUNT` and a
denormalised per-category `item_count`, never a scan.

**Counts do not animate on arrival.** Opening a screen renders the cached number
directly: no count-up, no flicker, no query. A count only animates when a refresh
genuinely changes it, ticking to the new value with a delta chip ("+128 new") that
fades after four seconds. A number that animates every time you arrive is noise
pretending to be life, and on a screen visited twenty times a day it is the first
thing that starts to feel cheap.

Empty state (no provider yet) is not an empty Home: it is the activation call to
action.

### 3.4 Section screens (Live, Movies, Series, Radio)

Two panes on TV and tablet: categories on the leading side, content on the trailing
side. One pane on phone, with categories as a horizontal chip row.

- Sort: provider order (default), A–Z, Z–A, recently added.
- The alphabet jump-bar appears when a list exceeds 500 items **and the sort is
  alphabetical** — on 40,000 movies scrolling is not a navigation method, but under
  provider order the letters would point nowhere, so the bar is hidden.
- Live rows show now/next from the guide when available, and the channel number
  when the provider supplies one.

**A section the provider does not carry still exists.** Live, Movies, Series and
Radio are permanent destinations. They are never hidden when a provider has none of
that kind: a rail that changes shape between providers teaches the user nothing, and
an entry that disappears reads as a bug rather than as information. The screen says
whose gap it is — naming the provider — and offers the two moves that help: a
section that does have content, and adding a provider that carries this one. The
header count reads `0`, from the same cached value every other section uses.

### 3.5 Guide (EPG grid)

Channels down, time across, "now" pinned. Only the visible window is queried —
visible channels × visible hours — which is why a 100,000-channel guide opens as
fast as a 100-channel one.

Left/Right moves through time, Up/Down through channels, and a long press
accelerates. Select on a current programme plays; on a future one, sets a reminder;
on a past one, plays catch-up **only when the provider actually offers it**.

### 3.6 Detail screens

- **Movie** — poster, synopsis, year, duration, Play / Resume, favourite, external
  player (when enabled).
- **Series** — seasons and episodes, with episodes fetched on open (the lazy load
  the data layer supports). Continue watching resumes the right episode.
- **Channel** — now/next, today's schedule, favourite, and a direct Play.

### 3.7 Player

Covered in §7.

### 3.8 Search

One screen, instant results, no Search button. Covered in §6.

### 3.9 Favorites · Continue watching · History

Mixed-kind lists that route by kind on select. Continue watching hides finished
items (the 95% rule lives in the domain and is applied by SQL). History keeps them.
Both offer "remove" as an explicit action — data the user can see, they can delete.

### 3.10 Settings

Grouped as in the map. Two entries carry product weight:

- **Playback → Player**: *internal (default)* or *external*. External is a listed
  choice with a picker, never a silent fallback.
- **Providers**: add, switch, refresh now, remove. Removing a provider states what
  it deletes and what it keeps (favourites survive; the catalogue does not).

---

## 4. Input models

### 4.1 Remote control (TV)

The design constraint that shapes everything: **five keys and no pointer.**

| Key | Behaviour |
|---|---|
| Up / Down | Move within a column; in the player, change channel |
| Left / Right | Move within a row; in the player, seek (VOD) or scrub the guide |
| Select | Activate; in the player, toggle the OSD |
| Back | Up one level; from a section, to Home; from Home, exit confirmation |
| Menu / Info | Context actions for the focused item |
| Play/Pause, FF/RW | Direct transport when present |
| 0–9 | Direct channel entry when the remote has a numeric pad |
| Guide | Opens the guide when the remote has a dedicated key |

Numeric keys and a guide key are **capability-detected, not assumed**: when they
are absent, the equivalent affordance appears on screen instead. That is the
`RemoteProfile` contract the platform layer already exposes.

Long-press repeats accelerate after 500 ms and cap at ten items per second — fast
enough to cross 40,000 movies, slow enough to stop where you meant to.

### 4.2 Touch (phone, tablet, touch TV)

Bottom navigation on phone, navigation rail on tablet. Tap to open, long press for
context actions, swipe down to dismiss the player, pull to refresh only where a
refresh is meaningful (a section, not a detail page).

Minimum target 48 dp; the TV focus geometry is separate and larger.

### 4.3 Pointer and keyboard (desktop, web)

Hover raises the same visual state as focus. Keyboard is first-class, not an
afterthought: arrows mirror D-pad, `/` focuses search, `Space` toggles playback,
`F` fullscreen, `Esc` backs out. Every action reachable by remote is reachable by
keyboard.

---

## 5. Focus management

Focus is a shared concern, not a per-screen one, and is specified as state rather
than as a widget property.

**Rules**

1. **Every screen declares its initial focus.** A screen that opens with nothing
   focused is a dead end on a remote.
2. **Row memory.** Returning to a row restores the item you left, not the first
   one. Kept per route, cleared when the catalogue is replaced.
3. **Focus lift, not focus glow.** The focused item scales ~6% with an elevation
   change; the border is a secondary signal for accessibility, never the only one.
4. **Focus is always visible without scrolling.** Lists keep the focused item at a
   stable position with a leading margin, so a held key never "loses" the cursor.
5. **Focus never leaves for an empty region.** If a direction has no target, the
   move is refused with a 40 ms nudge — silence is worse than a nudge.
6. **The rail owns its own focus.** Opening it remembers the current section;
   closing it returns focus exactly where it was.
7. **No focus traps.** Every overlay has a Back that dismisses it, including the
   on-screen keyboard.

**Loading and focus:** a list that has not loaded yet still accepts focus, so the
user's first keypress is never dropped. Placeholders are focusable; they simply
have no action until they resolve.

### 5.1 The state vocabulary

Six readings the user must never confuse. They are specified together because the
confusions only happen at the boundaries — "is this playing, or did I just watch
it?" is the question a sloppy state design leaves open.

Colour carries a fixed meaning throughout the product, and **no state is ever colour
alone**: each one also changes shape, position or opacity, so it survives colour
blindness, a miscalibrated panel, and a screen reader.

| Reading | Mark | Colour meaning |
|---|---|---|
| **Focused** | lift 4%, ring, glow — transient, follows the cursor | violet · navigation |
| **Selected** | indicator on the leading edge + coloured label — persistent | violet · navigation |
| **Loading** | hairline under the surface + a word ("refreshing") | amber · working |
| **In progress** | partial bar under the artwork, width = resume point | violet · navigation |
| **Recently watched** | full-width bar, artwork dimmed a third, "Watched" tag | neutral · the past |
| **Playing now** | full-width bar, inner edge, animated meter, "Playing" | aqua · now |

**One grammar, one location.** Everything the app knows about an item's history is
said with a 3 dp bar along the bottom edge of its artwork — nothing, partial, full
neutral, full aqua. A row with no bottom edge to spare (a channel line) runs the
same bar down its leading side: same four readings, rotated ninety degrees. One
place to look, no badges competing for the same corner.

**Recently watched takes no colour**, deliberately. History is not a status, and
making it neutral while "playing" is aqua and animated is what guarantees the two
can never be read as each other — the distinction survives a glance from three
metres.

**Playing is not selection.** A destination that is playing carries the aqua meter
at its *trailing* edge and borrows none of selection's marks, so "where I am" and
"what is on" are never the same mark. All six can be true at once — as they are when
the rail is opened while a channel runs — and each is still legible.

---

## 6. Search behaviour

- **No Search button.** Results update while typing.
- **Debounce 120 ms**, superseded queries cancelled — a fast typist issues one
  query, not eight.
- **Prefix matching:** `nov spo` finds "Nova Sports". Both sides are case-folded in
  Kotlin, so `новости` finds `Новости`.
- **Punctuation is treated as words, not operators**, so "Mission: Impossible" is a
  search rather than a syntax error.
- **Results grouped by kind** with counts, live first, and a "show all in Movies"
  action per group.
- **Minimum useful query is one character** — with prefix matching, one character
  is a real filter, and waiting for three is an artificial delay.
- **Empty query is an idle state, not zero results**: recent searches and a hint,
  never an error.
- **Voice search appears only where the platform provides it** — Assistant, Alexa,
  Siri. `PlatformServices.voiceSearch` being null means the microphone is absent
  from the UI rather than shown and broken.
- **On-screen keyboard** on TV is a single-row layout with the alphabet on one axis
  — three keypresses to reach any letter, not nine.

Budget: keystroke to rendered results under 50 ms on a low-end box, which the FTS
index makes achievable.

---

## 7. Player interaction model

### Principles

- **The internal player is the default**, always. External is an explicit setting.
- **The OSD is absent until asked for.** Playback starts clean; Select shows the
  OSD for 4 s, any key extends it.
- **Zapping is the primary action in live.** Up/Down changes channel with the OSD
  showing the new channel and its now/next; the list order is whatever list the
  user came from.
- **Affordances appear only when the provider supports them.** Timeshift and
  catch-up controls are rendered when `catchUpHours` is non-null and hidden
  otherwise — never disabled-and-visible.

### Layout

| Zone | Content |
|---|---|
| Top | Channel number, name, logo, now/next with progress |
| Bottom | Transport, position, duration, quality, audio/subtitle selectors |
| Right (on demand) | Channel list overlay, keeping playback visible |
| Center | Only transient feedback: pause, seek amount, buffering |

### Behaviours

- **Number entry** (live): digits compose for 2 s, then tune.
- **Seek** (VOD): Left/Right ±10 s, held accelerates to ±60 s, release commits —
  one seek, not fifty.
- **Resume**: anything past 10 s and under 95% resumes with a "start over" option.
- **Aspect ratio** cycles through fit / fill / original / 16:9 / 4:3, remembered per
  kind.
- **Errors are recoverable in place.** A network drop retries with backoff behind a
  quiet indicator; a format failure tries the provider's alternate stream before
  saying anything. The user sees an error only when the app has run out of moves.
- **External player** hands off the resolved URL and the position, and records the
  watch position as best it can on return.
- **Radio** uses the same player with an audio layout: artwork, station, now
  playing, and a visualiser that is disabled on low-memory devices.

---

## 8. Accessibility

- **Every focusable element has a label** describing what it is and what activating
  it does — "Nova Sports 1, now: Cup Final, ends 21:00".
- **Screen readers**: TalkBack, VoiceOver, and platform equivalents. Focus changes
  announce the item, not the container.
- **Contrast**: WCAG AA for text (4.5:1) and UI (3:1). Verified against the design
  tokens, not eyeballed.
- **Never colour alone.** Live/recording/locked states carry an icon or text.
- **Text scaling** to 200% without clipping; TV layouts reserve space for it.
- **Reduced motion** honours the platform setting and disables the backdrop,
  parallax and scale animations — the app remains fully usable with zero animation.
- **Captions**: size, colour and background are user settings and apply to the
  internal player.
- **No timing-critical interactions.** Nothing requires a fast press; the only
  timeout is the OSD, which is a hide, not a loss.
- **Audio description** track selection where the provider supplies one.

---

## 9. Internationalisation and RTL

- **Twelve languages**: English (default), Arabic, French, Spanish, German,
  Turkish, Italian, Dutch, Portuguese, Russian, Chinese, Japanese.
- **Detected on first launch** from the device, changeable in Settings, applied
  instantly without a restart, and persisted.
- **RTL is a layout direction, not a translation.** Arabic mirrors: navigation
  side, list growth, back/forward semantics, chevrons, progress fill.
- **What does not mirror**: the transport bar's play direction, timeline scrubbing,
  the guide's time axis, and media artwork. Time moves the same way in every
  language.
- **Numbers, dates and durations** use the locale's own formatting, including
  Arabic-Indic digits when the locale calls for them.
- **Fonts**: a Latin family with Arabic and CJK fallbacks that share metrics, so a
  language change does not reflow every list.
- **No text in images.** Strings never bake into artwork.
- **Layout tolerance**: every label is designed for +40% length (German) and
  vertical scripts are excluded rather than half-supported.

---

## 10. State design

### Loading

- **Skeletons, not spinners**, and only where content will appear. A spinner over a
  populated list is a lie about what is happening.
- **Nothing blocks input.** A loading list is focusable and scrollable.
- **Progressive**: content appears per group as the import commits it, which the
  data layer already supports.
- **A spinner is allowed only for an action the user just took** and only after
  300 ms, so a fast action never flashes one.

### Errors

Every error states three things: what failed, why, and the one action that helps.

| Situation | Message | Action |
|---|---|---|
| Wrong credentials | "Those details were rejected by the provider." | Edit details |
| Subscription expired | "This subscription expired on 3 March." | Contact provider / switch |
| All connections in use | "This subscription is playing on another device." | Retry |
| Host unreachable | "Can't reach the provider." | Retry / check network |
| Stream failed | Handled silently first; surfaced only after fallbacks | Try alternate / back |
| Guide missing | Not an error — the row shows the channel without now/next | — |

No error dialog is ever the only thing on screen if something useful can still be
shown behind it.

### Empty

An empty state names the reason and offers the fix: no favourites yet (with "browse
Live TV"), no results for a search (with the query echoed), a category the provider
left empty (say so — it is their doing, not a bug).

A whole section the provider does not carry is the same pattern with a stronger
claim, and it is the one empty state the user will actually meet: **"Nova IPTV
doesn't include movies"**. Naming the provider is the point — it tells the user the
app is working and the subscription is the limit.

**The primary action is chosen from what the provider does carry**, so it is never a
dead end and never a lie:

| The provider has | Primary action | Secondary |
|---|---|---|
| Live channels | Browse Live TV | Add another provider |
| Series but no movies | Open Series | Add another provider |
| Several sections, this one empty | Open Library | Add another provider |
| Nothing imported yet | Add a provider | — |

Two actions, never three. A productive next step is the requirement; a menu of them
is the noise that requirement exists to prevent.

---

## 11. Component library

Shared definitions, rendered per platform.

| Component | Variants | Notes |
|---|---|---|
| `MediaCard` | poster, landscape, channel, station | Image sized from device capability |
| `MediaRow` | standard, spotlight, continue-watching | Lazy, keyed, focus-memoried |
| `CategoryList` | rail, chips | Counts shown from cached values |
| `NowNextBar` | compact, full | Progress reflects real programme times |
| `GuideGrid` | — | Virtualised on both axes |
| `SectionHeader` | with count, with sort, with action | |
| `GlassPanel` | overlay, sheet, dialog | Blur is capability-gated |
| `FocusableSurface` | card, button, list row | Owns the lift + scale behaviour |
| `PrimaryButton` / `SecondaryButton` / `IconButton` | | Minimum sizes differ TV vs touch |
| `TextField` | text, password, numeric | TV variant opens the on-screen keyboard |
| `Keyboard` | alphabet, numeric, symbols | TV only |
| `ProgressIndicator` | linear, circular, skeleton | Determinate wherever a total exists |
| `EmptyState` / `ErrorState` | | Both require an action to be supplied |
| `PlayerOverlay` | live, vod, radio | Composition of the above |
| `SettingRow` | toggle, choice, action, info | |

Every component is specified with: states (default, focused, pressed, disabled,
loading), the tokens it consumes, and its behaviour under RTL and reduced motion.

---

## 12. Design system

The palette, typography, spacing, radius, elevation and motion already exist in
`:core:design` and are unchanged by this document. What follows is how they extend
across form factors — and, first, what may never change at all.

### Design invariants

Everything else in this document describes how Castivio is built today. This section
describes what may not be broken tomorrow.

An invariant is not a preference and not a guideline. It is a property the product
holds in every screen, on every platform, in every future feature — and a change
request that violates one is answered by changing the invariant deliberately, in a
commit of its own, or not at all. Products decay one reasonable exception at a time;
this list exists so that each exception has to be argued in public.

Each invariant names how it is held. **Where a rule can be enforced by the compiler
or by CI, it is — a rule that depends on someone remembering it is a rule that is
already broken.**

| # | Invariant | How it is held |
|---|---|---|
| 1 | **One visual language across the whole application.** Every surface uses the same focus, selection, playing, loading, progress and history marks defined in §5.1. | CI: no colour, type or shape literal outside `:core:design`. Review: a new mark must be added to §5.1 first. |
| 2 | **One meaning for each colour, everywhere.** Violet is navigation, aqua is now, amber is working, neutral is the past, danger is failure. A colour never means two things. | CI: literals blocked; semantic token names only. Review: a new meaning requires a new token, not a reused hue. |
| 3 | **One primary action per screen.** Exactly one — the thing the user came to do. Everything else is secondary or in a menu. | Review, and the component API: `ErrorState` and `EmptyState` take one required action and at most one secondary. |
| 4 | **No duplicate navigation patterns.** One rail, one bottom bar, one back rule. A feature does not invent its own way to move between screens. | Compiler: `Route` is a sealed type in `:core:navigation`; a feature cannot declare a destination the shell does not know. |
| 5 | **Three navigation levels from Home to playback, at most.** Live is two, movies is three, and detail is skippable. | Test: a route-graph test asserts the depth budget for every leaf that ends in `Route.Player`. |
| 6 | **No component exists in two inconsistent variants.** One `MediaCard` with variants as parameters, never a second card that is nearly the same. | CI: shared component names may be declared exactly once in the repository. |
| 7 | **Reuse before addition.** A new feature composes existing components; a new component is added to `:core:design` only when no combination of the existing ones expresses it — and then it is added *there*, not in the feature. | CI: `@Composable` public UI primitives outside `:core:design` are flagged. Review: the burden is on the addition. |
| 8 | **Performance is a feature.** No unnecessary recomposition, no allocation in a scroll, no animation that competes with a list, no work in composition. | CI: the existing performance budgets stay blocking. Review: every screen is profiled before it is called done. |
| 9 | **Accessibility and RTL are not optional.** Every component ships with a content description, a 3:1 contrast minimum on UI and 4.5:1 on text, a focus state that is not colour alone, and correct behaviour under `rtl`. | CI: direction-absolute APIs (`absolutePadding`, `Arrangement.Absolute`, `Alignment.Absolute`) are blocked outright. Review: the component is checked in both directions. |
| 10 | **Four states before implementation.** Every screen defines loading, empty, error and success *before* a line of it is written — in the mockup, then in the state holder's sealed type. | Compiler: a screen renders a sealed `ScreenState`, so the `when` is exhaustive and a missing state does not compile. |

Two of these are worth stating plainly, because they are the ones that get quietly
traded away under deadline:

**Invariant 7 is what keeps the design system a system.** The moment a feature adds
its own button "just for this screen", the system stops describing the product and
starts describing part of it. The cost of reuse is paid once, in the awkward
conversation about whether a component should take another parameter; the cost of
duplication is paid forever, in every future change that has to be made twice.

**Invariant 10 is what makes the other nine cheap.** Deciding the four states while
the screen is still a picture costs an hour. Discovering the empty state after the
screen is built costs a rewrite of its state holder, and usually produces the
spinner-over-a-list that §10 exists to prevent.

### Tokens

Tokens are semantic, never literal: `surface.raised`, `content.primary`,
`accent.brand`, `state.focus`. A screen that names a hex value is a defect — that
rule is what makes theming and platform variance tractable.

### Typography

One scale, two densities. TV steps up because of viewing distance; touch steps
down. The ratio is fixed so a layout designed once holds on both.

| Role | TV | Tablet | Phone |
|---|---|---|---|
| Display | 57 | 45 | 36 |
| Headline | 36 | 32 | 28 |
| Title | 28 | 24 | 20 |
| Body | 20 | 16 | 15 |
| Label | 16 | 14 | 13 |

Monospace is reserved for codes and MAC addresses, where character shape matters.

### Spacing

A 4 dp base grid, with a density multiplier: TV ×1.5, tablet ×1.15, phone ×1.
Screen margins are 48 dp on TV (overscan-safe), 24 dp on tablet, 16 dp on phone.

### Animation

| Purpose | Duration | Curve |
|---|---|---|
| Focus change | 120 ms | ease-out |
| Row scroll | 240 ms | ease-in-out |
| Screen transition | 280 ms | emphasised |
| Overlay in/out | 200 / 160 ms | ease-out / ease-in |
| Player OSD | 180 ms | ease-out |

Motion is functional: it shows where focus went and where a screen came from. Any
animation that competes with scrolling is capability-gated.

#### Three levels

Every animation in the product belongs to a level, and every level is a complete,
shippable experience — not a degraded one.

| | Full | Reduced | Disabled |
|---|---|---|---|
| Aurora backdrop | animates | static gradient | static gradient |
| Focus change | lift + cross-fade | instant ring and colour, no scale | instant |
| Playing meter | animates | static bars | static bars |
| Row scroll | 240 ms eased | instant | instant |
| Screen transition | 280 ms emphasised | cross-fade 120 ms | instant |
| Counts | tick on change | swap on change | swap on change |

The level is **chosen automatically** from device capability and the platform's
reduce-motion setting, then **overridden by the user** in Settings → Appearance. The
automatic choice is a starting point, never a ceiling: a user on a capable box who
wants stillness gets it, and a user on a weak stick who wants the full identity can
have it and live with the frame rate.

At *Disabled*, every one of the six states in §5.1 still reads. That is the test: if
a state needs motion to be legible, the state is designed wrong.

### Theme

Dark by default and designed for it. A light theme is a token swap, not a redesign.
Themes are per-platform-aware: on OLED televisions the darkest surface is true
black, on desktops it is not.

---

## 13. Layout rules by form factor

### TV (10-foot)

- Safe area: 5% inset on all sides. Nothing interactive outside it.
- Navigation rail on the leading edge, collapsed to icons, expanding on focus.
- Rows of 6 posters or 4 landscape cards at 1080p; scaled, not re-laid-out, at 4K.
- Minimum focusable size 64 dp; minimum text 16 sp.
- No hover, no scrollbars, no drag. Every action reachable in ≤3 keypresses from
  Home.

### Phone

- Bottom navigation with five destinations: Home, Live, Library, Search, Settings.
  Fixed — the five do not vary with what the provider carries, for the reason in
  §3.4. Material's active indicator marks the selection, not a top rule.
- Grids of 2 posters portrait, 3 landscape.
- The player is fullscreen with a swipe-down dismiss; picture-in-picture where the
  platform supports it.

### Tablet

- Navigation rail plus content, and a two-pane layout in landscape: categories
  beside the grid, series beside its episodes.
- Grids of 4–6 posters depending on width.
- Keyboard and pointer supported as first-class, not as phone-with-extra-space.

### Desktop

- Resizable window with a real minimum (960×600), multi-window later.
- Menu bar and keyboard shortcuts; the rail persists.
- The player supports fullscreen, always-on-top and external display.

---

## 14. Performance rules the UI must obey

These are not aspirations; they are constraints the data layer already meets and
the UI must not undo.

1. **Only visible items are rendered.** Page size 60, prefetch 30, at most 300 rows
   materialised — configured in one place, not per screen.
2. **Images decode at the device's poster width**, from `DeviceCapabilities`.
   Decoding a 4K JPEG into a 120 dp poster is the classic TV frame killer.
3. **No work in composition.** Formatting, sorting and filtering happen in the state
   holder or in SQL, never during layout.
4. **Counts come from cached values**, never from loading a list to measure it.
5. **The backdrop yields to frame rate**, as it already does: full animation on
   capable devices, a static gradient on low-memory ones.
6. **Every list has stable keys and content types**, so recycling works and identity
   survives paging.
7. **Screen transitions never wait on data.** The next screen appears immediately
   with its skeleton; content arrives into it.

Targets, matching the existing budgets: first frame under 1.2 s cold, Home usable
under 400 ms warm, keystroke-to-results under 50 ms, zap overhead under 200 ms,
60 fps while scrolling.

---

## 15. Delivery order

Proposed sequence, each item compiled and tested before the next:

1. Shell — navigation, rail, theme, focus engine, skeleton and error primitives.
2. Activation wired to the real importer (the ViewModel is drafted and set aside).
3. Home with live counts and real rows.
4. Section screens with paging, sorting and categories.
5. Detail screens, including lazy series episodes.
6. Player with the internal engine, then the external option.
7. Search.
8. Guide.
9. Favourites, Continue watching, History.
10. Settings, language switching, diagnostics.

Each step is a vertical slice that runs on a device, not a layer that waits for
another layer.

---

## 16. Open questions for you

1. **Activation code** — the portal that resolves a code to a playlist does not
   exist yet. Should the first release ship the other three methods and add it when
   the portal is ready, or wait for it?
2. **Parental control** — PIN-locked categories: in the first release or later?
3. **Second platform** — which one after Android TV? That choice decides whether
   the next investment is SQLDelight + Ktor (desktop/iOS) or a JS core (Tizen/webOS).
4. **Reminders** for future programmes — do they need to survive a reboot, which
   means a scheduled job per platform?

Nothing in this document is implemented yet. On approval, delivery starts at step 1
and each screen is built, compiled and tested before the next begins.
