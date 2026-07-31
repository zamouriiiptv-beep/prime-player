# Activation — consolidated specification

The first screen a new device opens on, where a subscription is added.

The visual composition is **approved and locked**. This document is the contract
the Compose implementation is written against; where it and the mockup disagree,
this document is wrong and should be corrected rather than worked around.

- Mockup: `design/mockups/activation-mac.html`
- Verification: `design/mockups/measure.js`
- Locked on: the composition reviewed at commit `897ccfa`

---

## 1. Scope

**In scope.** The activation screen's layout, content, state model, focus order,
measurement gates, and its localisation into all 37 shipping languages.

**Out of scope, and deliberately so.** Each of these is a separate workstream
with its own contract, and none may be invented from inside the UI:

| | Workstream | Status |
|---|---|---|
| B | Device identity | `DeviceIdentity` v1, frozen |
| C | Device key issuance and validation | **not designed** |
| D | QR pairing protocol | **not designed** |
| E | Web portal | **does not exist** |
| F | Licensing backend | **does not exist** |
| G | `VerificationRequest` authentication | **blocker, see §11** |

---

## 2. Screen architecture

Three bands, divided by two full-width hairlines. There is no large central
glass card and one is not to be reintroduced: the screen itself is the surface.

```
┌─ header ──────────────────────────────────────────────────────────┐
│ Add your subscription        · Castivio trial 7 days   ⊕ English  │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│   MAC ADDRESS                              ┌───────────┐          │
│   2F:19:EB:20:44:7C   ⧉                    │    QR     │          │
│                                            └───────────┘          │
│   DEVICE KEY                             Scan to set up …         │
│   482731              ⧉                                           │
│                                                                   │
│   [ Add playlist ]  [ ↻ Refresh ]                                 │
│   · status line (reserved height)                                 │
├───────────────────────────────────────────────────────────────────┤
│         Castivio is only a player and does not provide …          │
└───────────────────────────────────────────────────────────────────┘
```

**The only container is the QR plate**, and it exists because a QR is unreadable
without a light ground. Small controls may carry their own surface. Nothing else
may be wrapped in a box.

The two zones size to their content and the **pair** is centred. Neither
stretches: a stretching identity column was what produced the gulf down the
middle that the earlier composition was rejected for. The 60/40 relationship
follows from the content, measuring 67/33 in English.

This is the whole screen and there is no reduced variant of it. The QR and the
device key are drawn as shown even though what sits behind them is still to be
built; see §5.3.

---

## 3. Content, and the role of each element

| Element | Role | Emphasis |
|---|---|---|
| Title | Screen identity | Primary |
| Trial status | Castivio's licence, **not** the provider's subscription | Important secondary, clearly readable |
| Language control | Interactive; shows the current language in its own name | Interactive |
| MAC address | Public device identifier | **Anchor** — strongest element below the title |
| Device key | Six-digit pairing credential | Below the address, clearly distinct |
| Copy ⧉ ×2 | Utility, one per identifier, independent | Utility |
| Add playlist | Primary CTA | Primary |
| Refresh | Secondary action | Secondary |
| QR + caption | Convenience path to the portal | Secondary zone, never dominant |
| Status line | Playlist/refresh state only | Contextual |
| Legal line | Standing statement of what Castivio is | Low, still readable |

The word **subscription** belongs to the provider. Castivio's seven days are a
**trial** and always carry Castivio's name. A screen that said "Add your
subscription" beside "Your subscription: 7 days" would contradict itself.

---

## 4. Device identity

### 4.1 MAC address

Public identifier, derived by `DeviceIdentity` v1, which is **frozen**. Not a
secret. Possession of it must never, by itself, grant control of the device.

Rendered `2F:19:EB:20:44:7C` at 28sp mono on a phone and 42sp on a television,
always with `direction: ltr` and bidi **isolation**. This is not decoration: the
placeholder form `··:··:··:··:··:··` is entirely neutral characters and is
reordered outright by the bidi algorithm in an RTL paragraph. A licence address
that reads differently in Arabic is a support case in every RTL market.

Three of the 37 languages run right to left — العربية, فارسی, اردو — so the
mirroring is **structural**: direction-relative layout throughout, no positional
special-casing, and the invariant script already rejects a direction-absolute
API. The case to test is the mixed one, because it is the one this screen always
is: an RTL interface around an LTR address and a run of digits. Both must read
correctly and in the same order as they do in English.

### 4.2 Device key — final format

**Exactly six decimal digits, one group, no separator.**

```
482731
```

Numeric only. No dash, no space, no letters. Chosen to be read off a television,
typed into a phone, and said aloud.

`482731` is a **design fixture**. It exists so the mockup can be measured and so
this document can show the format. It is not a value the product ever produces,
and no build may treat it as one.

Rendered at 24sp mono on a phone and 32sp on a television — four steps below the
address, so the address stays the anchor — with wider tracking than the address
because these digits get transcribed and spoken.

**The UI must not generate it.** Not from the MAC, not from a random source, not
from anything. Issuance, validation, rotation, expiry and attempt limits are the
backend's contract (§11), and a key the client invented is a credential no server
can honour — worse than no key, because the user would read it out and be told it
is wrong.

The row itself is part of the approved screen and stays in it (§5.3). What the
prohibition binds is the **derivation**: nothing computes a key — not from the
MAC, not from a random source — and no temporary issuing scheme is stood up to
make the row work.

In the debug build the row shows **`482731`, as a fixture**, so the composition
can be judged on a device (§5.3.1). A fixture is a constant that a release build
cannot reach; it is not a key, it is not derived from anything, and it becomes a
real value only when the issuing contract exists (§11).

**Recorded objection, overruled and closed.** Six digits is 10⁶ values. Its
safety therefore rests entirely on server-side rate limiting and lockout, and
those are not optional hardening but a load-bearing part of the design. Noted
here so that whoever builds the backend inherits the reasoning rather than the
conclusion alone. The format is settled and is not to be reopened.

---

## 5. QR code

### 5.1 Final purpose

Open the Castivio web portal on the user's phone, with the pairing context
already established, so the subscription can be configured there and picked up
by the device.

```
TV shows QR → phone scans → portal opens, device identified
   → user configures subscription → device detects it via Refresh
```

### 5.2 What must not be built

There is to be **no interim QR**. Specifically forbidden: encoding the MAC
alone, encoding the device key, a temporary URL, a placeholder domain, a
transitional page, an improvised pairing protocol, or any navigation that
pretends the portal exists.

The QR is implemented **once**, correctly, when the portal and the pairing
protocol exist.

### 5.3 The QR stays visible — decided

**There is one design, and it is visually complete.** The QR zone is part of the
approved composition and is not hidden, reduced, or made conditional. The caption
stays as approved: *"Scan to set up on your phone"*.

What is pending is the **payload and the behaviour behind it**, not the element.
An earlier revision of this section proposed hiding the zone until the portal
existed; that proposal is withdrawn. It solved a problem the product does not
have — a design is allowed to describe the finished screen — and it created two
compositions where one was approved, which is how a screenshot, a focus order and
a set of measurements all start to drift.

So the two rules stand together and neither weakens the other:

- The screen is drawn complete, here and in the mockup.
- Nothing temporary is ever put behind the QR (§5.2). It is implemented once,
  with the real payload, when the portal and the pairing protocol exist.

The same reading governs the device key: the row is part of the approved screen,
and `482731` is the fixture that lets it be drawn and measured (§4.2). What must
not be improvised is the value a device would show — that waits for the issuing
contract.

### 5.3.1 What the debug build renders — decided

**The debug APK shows the complete composition**, so the screen can be judged on
a real phone and a real television: title, trial, language control, address,
copy, six-digit key, its copy, Add playlist, Refresh, the status region, the QR,
its caption, and the legal line. Nothing is stripped because its backend is
pending; backend-dependent values are isolated behind debug fixtures instead.

**The debug QR is a visual fixture and encodes nothing.** It is not a QR
generated from a payload — it is the symbol's *appearance*: three finder
patterns, the timing rows, and a deterministic pseudo-random data field, exactly
as the mockup draws it. There is no encoder in the path, so there is nothing in
it to decode, and it cannot leak or teach a payload it does not have.

That is a stronger guarantee than choosing a harmless string to encode, and it is
the one this codebase needs, because the shipped Slice 6 code does the opposite:

```kotlin
// MacIdentityViewModel.kt — to be deleted
qrBitmap(record.macAddress.value, QR_PIXELS)
```

The current implementation **encodes the raw MAC address**. §5.2 forbids it. The
encoder and its call site are removed rather than repointed — nothing temporary
goes behind the plate, and a working encoder next to an empty payload is an
invitation to fill it.

When the portal and the pairing protocol exist, a real encoder is introduced
once, driven by a real payload, and the fixture is deleted in the same commit.

### 5.4 Sizing

Driven by module pitch, never by composition. A 17-character payload is a
version 1 symbol — 21 modules plus a two-module quiet zone.

| Frame | Plate | Code | Pitch |
|---|---|---|---|
| 873×393 | 148dp | 130dp | 5.2dp/module |
| 800×360 | 130dp | 114dp | 4.6dp/module |
| TV 960×540 | 196dp | 172dp | 6.9dp/module |

Floor: **3.0dp per module.** A real portal URL is longer than an address and will
push the symbol to version 2 or 3 (25 or 29 modules); the plate sizes above must
be re-derived at that point, not assumed.

---

## 6. State models

### 6.1 Refresh

Independent state machine. Refresh answers one question: *has a subscription been
attached to this device yet?* It never mutates the identity.

| State | Button | Status line |
|---|---|---|
| `Idle` | ↻ Refresh | empty |
| `Checking` | ◌ Checking… | empty |
| `Found` | ↻ Refresh | ● Playlist found *(success)* |
| `Empty` | ↻ Refresh | ● No playlist found yet *(neutral)* |
| `Error` | ↻ Refresh | ● Couldn't refresh — try again *(danger)* |

```
Idle ──press──▶ Checking ──┬─ found ─▶ Found ──▶ (leave activation)
                           ├─ none ──▶ Empty ──press──▶ Checking
                           └─ fail ──▶ Error ──press──▶ Checking
```

`Found` is terminal for this screen: the app proceeds. `Empty` and `Error` return
to `Checking` on the next press. Nothing here is a dialogue — a routine "nothing
yet" must never be something the user dismisses with a remote.

### 6.2 Copy — two independent instances

`CopyState = Idle | Copied`, one per identifier. **Copying one must not alter the
other.**

Confirmation is a glyph and colour swap **inside an unchanged box**: the icon
becomes a check in the success token. The control must not resize and nothing
around it may move — growing it to fit the word would shove the address sideways
on every press, and on a television it would move a control out from under the
focus ring.

`Copied` reverts to `Idle` after a short delay, or immediately if the other
identifier is copied.

**The status line is not used for copy feedback.** It belongs to playlist and
refresh state alone.

### 6.3 Status line

Reserved height whether or not it has anything to say — 20dp on a phone, 24dp on
a television — so no state change moves geometry.

---

## 7. Focus order

Deterministic, and identical in LTR and RTL because it follows document order
rather than position:

```
1  Language      2  Copy MAC      3  Copy device key
4  Add playlist  5  Refresh
```

All five exist on every build. A control that is drawn is in the focus order —
never present-but-skipped, which strands a remote on an invisible stop.

The QR is **not focusable**. It is read by a camera, not pressed. It gains focus
only if it ever acquires a real action.

Initial focus: **Add playlist**.

Touch targets: 48dp phone, 56dp television. A control may be drawn smaller than
its target — the language chip is 32dp — but the target must then be declared
and expanded in the implementation, never left implicit.

---

## 8. Responsive rules

Layout responds to measured width **and** height. `DeviceClass.scale` is dead
code and is to be deleted; a single multiplier is not a responsive strategy.

Reference frames: **873×393**, **800×360**, **TV 960×540**.

What changes between phone and television is decided by space, not by a factor:
the address at 28sp against 42sp, targets at 48dp against 56dp, the QR caption
beside the code on a phone and beneath it on a television, because a television
has height to spend and a phone in landscape does not.

---

## 9. Typography and contrast

Approved line-height rise, applied in full: `bodySmall` 12.5/20,
`headlineMedium` 22/32, `headlineLarge` 28/40, `labelSmall` (overline) 11/18.
Measured against glyph ink from Canvas metrics — not against `line-height:
normal`, which reports the font's recommended spacing and called every Arabic
line a failure while it rendered perfectly.

| Token | Worst ink | Line | Headroom |
|---|---|---|---|
| `bodySmall` @20 | 17.0dp (Thai) | 20 | 3.0dp |
| `bodyMedium` @22 | 19.4dp (Arabic) | 22 | 2.6dp |
| `headlineMedium` @32 | 28.7dp (Thai) | 32 | 3.3dp |
| `headlineLarge` @40 | 35.5dp (Arabic) | 40 | 4.5dp |
| `overline` @18 | 15.0dp (Thai) | 18 | 3.0dp |

The overline was the last of them and is now **closed at 11/18**: it carries the
MAC and device-key labels, 11/16 left 1dp against Thai, and 11/18 gives it the
same 3dp the other four carry.

One measurement sits below 3dp and stays there: `.days`, the trial counter, at
2.2dp against Arabic. It is a bounded string — a numeral and a unit — not a
translated sentence, so it has no room to grow into the way a label does.

**The type system is not tuned for Latin.** Eleven writing systems are in the
shipping set — Latin, Arabic, Perso-Arabic, Cyrillic, Greek, Devanagari, Bengali,
Thai, Chinese, Japanese, Hangul — and the line heights above were raised because
Latin was the wrong yardstick, not as a margin of comfort. They are not to be
reduced because a language list changed.

Contrast: secondary information must read as chosen, not disabled. Labels are
silver, the trial is 14sp at weight 500 with the count in violet, the legal line
is muted rather than faint. Nothing is solved by making everything white, and
nothing may use disabled-like opacity for information that is not disabled.

Contrast holds over both the bright and dark regions of the aurora — it is not to
be validated against a single flat colour.

---

## 10. Localisation

Castivio is a global product. The language set is not a European set with
additions; it is chosen to cover Western, Central and Eastern Europe, the
Nordics, the Balkans, North America, Latin America, the Arab and Persian
markets, South Asia, East Asia and Southeast Asia.

### 10.1 The 37 user-facing languages — final

| | | | | |
|---|---|---|---|---|
| 1 English | 2 العربية | 3 Français | 4 Español | 5 Deutsch |
| 6 Italiano | 7 Português | 8 Nederlands | 9 Türkçe | 10 Русский |
| 11 Українська | 12 Polski | 13 Română | 14 Magyar | 15 Čeština |
| 16 Slovenčina | 17 Ελληνικά | 18 Svenska | 19 Dansk | 20 Norsk |
| 21 Suomi | 22 Български | 23 Hrvatski | 24 Српски | 25 Shqip |
| 26 فارسی | 27 اردو | 28 हिन्दी | 29 বাংলা | 30 Bahasa Indonesia |
| 31 Bahasa Melayu | 32 ไทย | 33 Tiếng Việt | 34 中文 | 35 日本語 |
| 36 한국어 | 37 Filipino | | | |

**All 37 are in scope now.** The screen is not localisation-complete until every
required string exists in every one of them. There is no phased subset and no
later expansion.

Each language appears in the selector **once**, under its own native name, never
as a locale code. Portuguese is one entry. Chinese is one entry. A resource
directory is not a language.

### 10.2 Resource mapping — 41 directories for 37 languages

A user-facing language is a choice a person makes. A resource directory is how
Android finds a string. They are not the same count, and the four places they
diverge each have a reason.

**One entry, two directories — genuinely necessary.**

- **中文 → `values-b+zh+Hans`, `values-b+zh+Hant`.** Simplified and Traditional
  are different writing systems, not regional wording: the glyphs differ and so
  does the vocabulary. One `values-zh` would be wrong for Taiwan and Hong Kong.
  The script is taken from the system locale (`zh-Hans-*` or `zh-Hant-*`) and
  defaults to Simplified when the system says only `zh`. **Stated limitation:**
  with one selector entry, a user on a Simplified system cannot choose
  Traditional. Ranked below the rule that the selector shows one Chinese.
- **Português → `values-pt`, `values-pt-rPT`.** `values-pt` carries **Brazilian**
  Portuguese and `values-pt-rPT` European, because Android falls back from any
  Portuguese region to `values-pt` and the larger audience should be the one that
  never falls back. One selector entry; the region comes from the system.

**Legacy code aliases — required by `minSdk 21`, not extra languages.** Android
resolves some ISO codes by their obsolete form, and a device that reports the old
code does not match a directory named with the new one:

| Language | Directory | Alias | Why |
|---|---|---|---|
| Bahasa Indonesia | `values-in` | — | Java rewrites `id` to `in`; `values-id` never matches |
| Norsk | `values-nb` | `values-no` | devices below API 24 report `no` |
| Filipino | `values-fil` | `values-tl` | same, for the pre-`fil` code |

`values-no` and `values-tl` hold the same strings as the directories they alias.

**Српски → `values-sr`, Cyrillic.** Revised from the earlier `b+sr+Cyrl` note:
Castivio ships one Serbian, so the unqualified directory is correct and matches
every Serbian device, where the script-qualified one would leave a `sr-Latn`
device on English.

Arithmetic: 36 directories for the languages other than English, minus one for
Chinese and plus two for its scripts, plus `pt-rPT`, plus the two aliases, plus
`values/` for English — **41 directories, 37 languages.**

### 10.3 Translation quality

Strings are translated for meaning, not word for word, and the terminology is
part of the meaning: *Add your subscription*, *Castivio trial · 7 days*, *MAC
Address*, *Device Key*, *Add playlist*, *Refresh*, *Checking…*, *Playlist found*,
*No playlist found yet*, *Couldn't refresh — try again*, *Scan to set up on your
phone*, *Copy*, *Copied*, the legal line, and every accessibility description.

The distinction in §3 survives translation or the translation is wrong: the seven
days are **Castivio's trial**, never the provider's subscription. A language whose
wording lets the two be read as one is a defect, not a nuance.

### 10.4 Completeness, which is checked and not asserted

**Fail loud.** A missing key must never render empty text, `undefined`, or a
silent fallback. The mockup renders `⟨key⟩` for anything neither table has, and
the measuring script fails on empty, marked or `undefined` content. This exists
because it happened: a button rendered blank and all 27 combinations passed, an
empty 48dp button being exactly 48dp tall. **A geometrically valid empty screen
is not a passing test.**

Resource validation covers **all 37**, independently of the geometry matrix, and
fails on a missing translation, an empty one, an unresolved key, `undefined`
content, a placeholder left in, or a fallback to English where a translation is
required. Passing the stress subset is not evidence about the other languages and
is never reported as though it were.

### 10.5 The language selector

Required on this screen, and the mechanism is in §12.

First launch: read the system language; use it if Castivio has it; otherwise
English. The user can change it from this screen without going anywhere else.

The list shows native names — `Français`, not `FR`. A compact form is permitted
only in the header control, where the approved composition has room for one word.

**37 options are not a list to pour onto a screen.** The picker is an overlay —
a sheet, dialogue or menu — and it must work on a phone, on a television, under a
thumb and under a D-pad, which means it needs scrolling with a stable focus model
and, at this length, a way to narrow the list. It is designed as a mockup and
approved before it is written, like every other screen.

**The picker does not touch the activation composition.** Its length is a fact
about the overlay, not a reason to move anything behind it.

### 10.6 Per-app locales — pending, and deliberately not resolved here

`AppCompatDelegate.setApplicationLocales` is the correct mechanism for a *durable*
per-app language, but `:app` uses `ComponentActivity` and adopting it means taking
an `appcompat` dependency and changing the activity base class.

That is an architectural decision about the whole application, not a detail of one
screen, so it is **held as its own decision and deferred**, and it will be
explained before it is made. The activation work adds no `appcompat` dependency
and does not change the activity base class.

This gates persistence, not localisation: all 37 languages are resourced now, the
system language is honoured on first launch, and the selector is laid out,
measured and focusable. What survives a process death is the subject of that
decision.

---

## 11. Blockers and pending contracts

### 11.1 `VerificationRequest` carries no secret — **production blocker**

```kotlin
data class VerificationRequest(
    val macAddress: MacAddress,
    val identityVersion: Int,
    val provenance: IdentityProvenance,
    val legacyAddresses: List<MacAddress> = emptyList(),
    val cached: EntitlementRecord? = null,
)
```

Every field identifies; none authenticates. The weakness of treating an address
as authentication is already present in the **licensing** contract, not only in
the portal that does not exist. It is harmless today because there is no server
to lie to, and stops being harmless the moment there is one.

**Must be resolved before any production licensing or pairing backend is
implemented. Not to be modified as part of the UI work.**

### 11.2 Pending contracts

| | Needed before |
|---|---|
| Device key issuance, validation, rotation, rate limiting | the key means anything |
| QR pairing protocol — short-lived, single-use, not the device key | the QR is implemented |
| Web portal and its domain | the QR caption is true |
| Server-backed `EntitlementSource` | a release build is fit to publish |

A code displayed on a television is visible to the room and can be photographed.
Whatever the pairing protocol becomes, the QR must not carry a long-lived
credential.

---

## 12. Verification gates

Run from `design/mockups/` — non-zero exit on any failure.

```sh
node measure.js               # 27  frame × language
node measure.js --state all   # 96  frame × language × state
```

Per combination: no scroll, nothing painting outside, no clipped text, every
string key resolves, the address has room, no script's ink exceeds its line box,
the legal line is on screen, the header is one row, every control meets its
target, the QR meets its module pitch.

State coverage: resting, checking, found, none, error, copied-mac, copied-key,
focus-copy. Every one of them changes the layout, which is why they are measured
rather than looked at: a spinner and a longer verb widen the refresh button, a
status sentence appears under the actions and can wrap, and a focus ring is drawn
outside the control it belongs to.

### 12.1 The stress languages are representatives, not the shipping set

**Every one of the 37 ships (§10).** A smaller set carries the geometry matrix,
chosen so that each member stands for a failure class no other member can
produce. Passing it says the layout survives those classes — it says nothing
about which languages exist, and it is never reported as though it did.

| | Failure class it stands for |
|---|---|
| `en` | baseline |
| `ar` | RTL, bidi, Arabic metrics |
| `fa` or `ur` | second RTL, mixed script, Perso-Arabic shaping |
| `de` | expansion |
| `fi` | expansion, unbroken compounds |
| `hi` | Devanagari — headline and matras above and below |
| `bn` | Bengali conjuncts |
| `th` | tallest ink; marks above and tone marks below |
| `zh` | line breaking without spaces |
| `ja` | line breaking without spaces, mixed kana and kanji |
| `ko` | Hangul metrics |
| `ru` or `sr` | Cyrillic |

The matrix grows when measurement finds a class none of these produces; it does
not shrink to make a run faster.

Current, against the nine languages configured today: **27/27** and **96/96**.
The set above is the target for the Kotlin work, at which point the resting
matrix is 3 frames × 12 languages and the state matrix runs the four worst.

### 12.2 Resource completeness, separately

A pass covering all 37 languages, run independently of the geometry matrix, on
the rules in §10.4. Geometry and completeness are different claims and neither
is allowed to stand in for the other.

---

## 13. Open decisions

1. **`appcompat` for per-app locales** — dependency and base-class change
   (§10.6). Held as an application-wide architectural decision, to be explained
   before it is made. It gates whether a chosen language *persists*, not whether
   the languages exist; the activation work does not touch it.
2. **The language picker's presentation** — an overlay for 37 entries that works
   under a thumb and under a D-pad (§10.5). Mockup and approval first, as with
   every screen. It does not alter the activation composition.

Closed since the previous revision: the 37-language set, one entry per language,
and the 41-directory mapping (§10); the device key format, the prohibition on
deriving one, and the debug fixture (§4.2); the QR's purpose, its remaining
visible, and the debug fixture that encodes nothing (§5.3, §5.3.1); `overline` at
11/18 (§9). `VerificationRequest` is not an open decision — it is a production
blocker recorded in §11.1 and in `RELEASE_CHECKLIST.md` §1b, owned by the
licensing backend and out of scope for this screen.
