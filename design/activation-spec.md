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

### 10.2 Resource mapping — 39 directories, and how that number is settled

A user-facing language is a choice a person makes. A resource directory is how
Android finds a string. They are not the same count.

**Thirty-one languages need nothing said about them.** One directory each,
`values-<code>`, with the ISO 639-1 code: `ar fr es de it nl tr ru uk pl ro hu cs
sk el sv da nb fi bg hr sr sq fa ur hi bn ms th vi ja ko`. English is the default
and lives in `values/`.

**Six need a decision, and here they are with the reasoning:**

| Language | Canonical | Qualifier(s) | Alias | Why |
|---|---|---|---|---|
| 中文 | `zh-Hans`, `zh-Hant` | `values-b+zh+Hans`, `values-b+zh+Hant` | — | different writing systems, not regional wording |
| Português | `pt-BR`, `pt-PT` | `values-pt` (Brazilian), `values-pt-rPT` | — | every `pt-*` region falls back to `values-pt`, so the larger audience goes there |
| Bahasa Indonesia | `id` | `values-in` | — | the platform reports the obsolete code; see below |
| Filipino | `fil` | `values-b+fil` | — | old-style qualifiers take two letters, `fil` has three |
| Norsk | `nb` | `values-nb` | — | `nb` is a valid ISO 639-1 code and what Android reports |
| Српски | `sr` | `values-sr` (Cyrillic) | — | Castivio ships one Serbian |

Chinese and Portuguese are the only two that need a second directory. **31 + 1
(`values/`) + 8 for those six = 39.**

**Why not 41.** The previous revision added `values-no` and `values-tl` as
compatibility aliases. They are removed. Neither is justified by anything
verified: `nb` is a current ISO 639-1 code that Android's own locale lists use,
and an alias added "because the old code existed" is a directory somebody has to
keep in step with 37 others forever.

**Српски** is also revised, from `b+sr+Cyrl` to `values-sr`. Castivio ships one
Serbian, so the unqualified directory matches every Serbian device, where the
script-qualified one would leave a `sr-Latn` device reading English.

#### What was verified, what was not

Locally verifiable, and verified: **the JVM on this machine is not an oracle for
Android's locale handling.** JDK 21 normalises Indonesian to the *modern* code —
`new Locale("in").getLanguage()` returns `id` — which is the opposite of Android,
where the obsolete code is what the platform reports. The old behaviour is
reachable with `-Djava.locale.useOldISOCodes=true`, and that flag flipped the
result. A test written on this machine's default would have "confirmed"
`values-id` and been wrong on every device.

Not verifiable here, and therefore **not asserted**: whether AAPT2 canonicalises
a locale qualifier when it writes the resource table, and how the runtime matches
it on API 21 against API 34. `dl.google.com` is unreachable from this sandbox, so
there is no `aapt2`, no emulator, and no way to run the only experiment that
answers it.

So the number above is a **proposal with an argument behind it, not a verified
fact**, and it is not frozen by being written down. What freezes it is a test.

#### The test that settles it, instead of the argument

Each of the 39 directories carries one extra string, `locale_sentinel`, whose
value is that directory's own tag. An instrumented test walks all 37 canonical
locales, sets each one, resolves `locale_sentinel`, and asserts it came from the
directory that locale is supposed to reach. Run on **API 21 and on a current API
level**, because those are two different resolvers.

That turns "does Android match this directory?" from a question three people
answer differently into one that fails the build. If the answer is 38, or 40, the
mapping changes and this section is corrected — **correct resolution is the
requirement; 39 is only today's best answer to it.**

### 10.2.1 What one entry does for Chinese and Portuguese

Both single entries resolve to a variant, and the rule is the same for each:
**take the variant from the system when the system has an opinion, fall back to
the larger audience when it does not, and persist what was resolved — never the
bare language.**

Persisting `zh-Hans` rather than `zh` matters: a user who picks 中文 on an English
phone and later switches the phone to Traditional should not silently have
Castivio change script underneath them. The choice was made once and is kept.

**中文**

| System locale when 中文 is chosen | Resolves to |
|---|---|
| `zh-Hant-*`, or region `TW`, `HK`, `MO` | `zh-Hant` |
| `zh-Hans-*`, or region `CN`, `SG`, `MY` | `zh-Hans` |
| any `zh` with nothing else to go on | `zh-Hans` |
| not Chinese at all | `zh-Hans` |

**Português**

| System locale when Português is chosen | Resolves to |
|---|---|
| `pt-PT` | `pt-PT` |
| any other `pt-*`, including `pt-BR` | `pt-BR` |
| not Portuguese at all | `pt-BR` |

**The limitation, stated rather than buried.** A Traditional-script reader on a
non-Chinese phone gets Simplified and has no way to change it, because that would
need a second visible entry. The same is true of a European Portuguese speaker on
an English phone. This is the price of one entry per language and it is being
paid deliberately. If it ever needs solving, the way that does not break the rule
is a script or region choice **inside** the row — not a second row.

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

**The picker does not touch the activation composition.** Its length is a fact
about the overlay, not a reason to move anything behind it. It is drawn in
`design/mockups/language-picker.html` and measured with the same harness.

**A grid, not a list.** The phone frame is landscape at 393dp, where height is
the scarce dimension and a name is at most sixteen characters. Measured: three
columns show **15 of 37 at once — 2.5 screens**; one column would show 5 and take
7.4. The television takes four columns and shows **24 — 1.5 screens**.

| | Panel | Columns | Row | Visible | Screens | Dismissal |
|---|---|---|---|---|---|---|
| Phone 873×393 | 689×369 | 3 | 48dp | 15 | 2.5 | close control |
| TV 960×540 | 832×472 | 4 | 56dp | 24 | 1.5 | Back, and it says so |

**Ordering: the approved order, unchanged and unsorted.** Sorting 37 native names
alphabetically means sorting Ελληνικά against Русский against 한국어, which is a
sort by code point wearing a dictionary's clothes. The given order groups by
script and region — four Cyrillic names together, the Arabic-script languages
together — which is what makes the grid scannable. It is also stable, so a
returning user's language is where it was last time. One entry each; the selected
language is highlighted **in place** and never copied to the top.

**No search field, on either device.** Two reasons, and the first is the stronger:
the list is already indexed by the requirement that each language appears under
its own name. العربية among Latin names is not something anyone has to read to
find — the script is the index, and a reader is looking for their own letters. An
alphabetical list of English exonyms would need search; this one does not. The
second is the trade: on a television, an on-screen keyboard — the worst input
surface a remote has — to save at most two directional presses on a list 1.5
screens long; on a phone in landscape, the IME covers the list it is searching.
Both are worse than the scroll they replace. If a later measurement contradicts
this, the measurement wins.

**Focus is not selection**, and on a television both must be legible at once.
They use different channels, and selection never rests on hue alone:

- **Selected** — a filled surface, the name at full weight, and a check. Three
  cues, so the state survives a viewer who cannot separate the fill from the
  ground, and survives a photograph of a television taken at an angle.
- **Focused** — the ring the rest of Castivio uses, drawn outside the row's own
  surface so it reads on a selected row as well as an unselected one.
- A row may be both, and when it is, neither becomes ambiguous.

Focus opens on the selected language and returns to the header control when the
picker closes.

**Direction is carried by two different elements, on purpose.** The cell is laid
out and aligned by the interface; the name sits in a `<bdi>` that isolates it and
gives it its own direction. Putting the direction on the cell was tried and was
wrong twice over — it pushed Arabic names to the far end of their column, losing
the one alignment a scannable list depends on, and it would have done the same to
every Latin name the moment the interface turned around.

### 10.6 Language persistence — the behaviour is locked

Not a preference and not an open question:

1. **First launch** — read the system language. If Castivio has it, open in it.
   Otherwise open in English.
2. **After a manual choice** — that choice is Castivio's language and it
   **survives closing and reopening the app**.
3. The system language **never overrides** an explicit choice, on any later
   launch.

> Device in Deutsch → first launch is Deutsch. User picks English → every launch
> after that is English, whatever the device says.

Only the mechanism is open, and §10.6.1 compares the candidates.

### 10.6.1 How to implement it — four options

| | **A** `AppCompatDelegate` | **B** framework `LocaleManager` | **C** persisted tag + `ContextWrapper` | **D** Compose-only |
|---|---|---|---|---|
| First-launch detection | library reads system, no stored value | framework, API 33+ only | we read `Locale.getDefault()` and match the 37 | same |
| Applying a choice | `setApplicationLocales` | `setApplicationLocales` | write the tag, recreate the activity | swap a provided `Context` |
| Persistence | library store, or the framework on 33+ | framework, 33+ only | our own store — the one thing fully in our hands | needs C underneath anyway |
| Visible in Android Settings | **yes**, on 33+ | **yes**, on 33+ | yes on 33+ if we also call `LocaleManager` | no |
| Activity recreation | yes | yes | yes | **no** |
| `minSdk 21` | yes, backported | **no** — dead below 33 | yes | yes |
| Compose | fine, but drags `Theme.AppCompat` in | fine | fine | fine, and nothing else |
| Dependencies | `appcompat` **+ `AppCompatActivity`** | none | none | none |
| Architectural cost | **base-class change**; an AppCompat theme parent; a view toolkit Castivio uses nothing else from | none, and no behaviour below 33 | ~80 lines in `:app` and `:data:preferences`, all ours to test | leaks: only Compose sees it |
| Long-term | Google maintains it | the platform's own direction | we own a small, boring, testable thing | breaks the day the first notification is posted |

Three of the four are eliminated by facts rather than taste. **B** does nothing on
the great majority of devices Castivio targets. **D** localises the composition
and nothing else, so the first notification, `Toast` or accessibility
announcement is in the wrong language — and it needs C's storage regardless.
**A** works, and its price is `AppCompatActivity` plus an AppCompat theme parent
in an application whose entire UI is Compose and Material 3; that is a view
toolkit and a theming system adopted to hold one string.

**Recommendation: C, with the framework alongside it on API 33+.**

- The tag lives in `:data:preferences`, next to every other preference.
- `:app` wraps `attachBaseContext` with a configuration carrying the resolved
  locale, so the whole app — not only the composition — is in that language.
- On API 33+ the same choice is also written through `LocaleManager`, so Android
  Settings shows and controls it, and `android:localeConfig` lists the 37 tags so
  Castivio appears in the system's per-app language screen at all.
- `ComponentActivity` stays. No `appcompat`.
- Resolution is a pure function — system locale plus stored tag in, canonical tag
  out — which is where the Chinese and Portuguese rules of §10.2.1 live, and it
  is unit-tested with no emulator.

This is a recommendation, not a decision. It is not implemented until approved.

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
node measure.js                              # 27  activation, frame × language
node measure.js --state all                  # 96  × state
node measure.js --file language-picker.html  # 24  picker, 2 frames × 12 languages
```

The harness refuses to run without one Noto face per script in the shipping set.
That is not politeness: `bn`, `ja` and `zh` were measured for weeks through a
fallback face that has none of their glyphs, and reported comfortable headroom
every time. A measurement taken through the wrong font is not a weaker
measurement, it is a different one — so a run that cannot measure what it claims
to now exits instead of warning. With the right faces installed, the activation
numbers moved: `headlineLarge`'s worst case is Thai at 36.3dp, not Arabic at
35.5, and the phone-800 address has 133.3dp of spare rather than 153.6. Still
27/27, but they are the device's numbers now.

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

Current: activation **27/27** and **96/96** against the nine configured today;
picker **24/24** against twelve, which is the full set above bar `ur` and `sr`,
each covered by its script partner. Activation moves to the same twelve in the
Kotlin work.

### 12.2 Resource completeness, separately

A pass covering all 37 languages, run independently of the geometry matrix, on
the rules in §10.4. Geometry and completeness are different claims and neither
is allowed to stand in for the other.

---

## 13. Open decisions

1. **The language-persistence mechanism** (§10.6.1) — behaviour is locked,
   mechanism is not. Recommendation on the table: a persisted tag and a
   `ContextWrapper`, with `LocaleManager` alongside it on API 33+, no `appcompat`
   and no base-class change. Awaiting approval.
2. **The picker's visual design** (§10.5) — drawn and measured, awaiting
   approval.
3. **The resource mapping's arithmetic** (§10.2) — 39 is a proposal with an
   argument behind it, not a verified fact, and the sentinel test settles it on a
   device. Correct resolution is the requirement; the number is an output.

Closed since the previous revision: the 37-language set, one entry per language,
and the 41-directory mapping (§10); the device key format, the prohibition on
deriving one, and the debug fixture (§4.2); the QR's purpose, its remaining
visible, and the debug fixture that encodes nothing (§5.3, §5.3.1); `overline` at
11/18 (§9). `VerificationRequest` is not an open decision — it is a production
blocker recorded in §11.1 and in `RELEASE_CHECKLIST.md` §1b, owned by the
licensing backend and out of scope for this screen.
