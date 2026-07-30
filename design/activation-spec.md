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
localisation and measurement gates.

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

### 4.2 Device key — final format

**Exactly six decimal digits, one group, no separator.**

```
482731
```

Numeric only. No dash, no space, no letters. Chosen to be read off a television,
typed into a phone, and said aloud.

Rendered at 24sp mono on a phone and 32sp on a television — four steps below the
address, so the address stays the anchor — with wider tracking than the address
because these digits get transcribed and spoken.

**The UI must not generate it.** Not from the MAC, not from anything. Issuance,
validation, rotation, expiry and attempt limits are the backend's contract (§11).
Until that contract exists the key is display-only.

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

### 5.3 Consequence for the current build — needs a decision

The mockup shows the final caption, *"Scan to set up on your phone"*, because
this is the final interface being designed.

That string is **not true today**, and the "no interim behaviour" rule forbids
making it true cheaply. Any build shipped before the portal exists therefore has
exactly two honest options:

- **A.** Hide the QR zone entirely until the portal is live.
- **B.** Show the QR with a caption describing what the payload actually is.

B contradicts §5.2 unless the payload is also changed, so **A is the only option
consistent with the locked decisions.** Flagged rather than chosen: it changes
what a pre-portal debug APK looks like.

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

Approved line-height rise, already applied: `bodySmall` 12.5/20,
`headlineMedium` 22/32, `headlineLarge` 28/40. Measured against glyph ink from
Canvas metrics — not against `line-height: normal`, which reports the font's
recommended spacing and called every Arabic line a failure while it rendered
perfectly.

| Token | Worst ink | Line | Headroom |
|---|---|---|---|
| `bodySmall` @20 | 17.0dp (Thai) | 20 | 3.0dp |
| `bodyMedium` @22 | 19.4dp (Arabic) | 22 | 2.6dp |
| `headlineMedium` @32 | 28.7dp (Thai) | 32 | 3.3dp |
| `headlineLarge` @40 | 35.5dp (Arabic) | 40 | 4.5dp |
| **`overline` @16** | **15.0dp (Thai)** | **16** | **1.0dp** ⚠ |

**Open item.** `overline` (11/16) carries the MAC and device-key labels and has
1dp of headroom against Thai. 11/18 measures 3dp. Same defect class as the three
already raised; not applied, awaiting a decision.

Contrast: secondary information must read as chosen, not disabled. Labels are
silver, the trial is 14sp at weight 500 with the count in violet, the legal line
is muted rather than faint. Nothing is solved by making everything white, and
nothing may use disabled-like opacity for information that is not disabled.

Contrast holds over both the bright and dark regions of the aurora — it is not to
be validated against a single flat colour.

---

## 10. Localisation

37 languages. Nine are measured as stress cases: `en` baseline, `ar` RTL and
bidi, `de` and `fi` expansion, `th` `hi` `bn` line boxes, `ja` `zh` line breaking
without spaces.

Android qualifiers, as agreed: `b+zh+Hans`, `b+zh+Hant`, `nb`, `b+sr+Cyrl`, `pt`
and `pt-rBR` — 38 resource sets for 37 languages, English as the default.

**Fail loud.** A missing key must never render empty text, `undefined`, or a
silent fallback. The mockup renders `⟨key⟩` for anything neither table has, and
the measuring script fails on empty, marked or `undefined` content. This exists
because it happened: a button rendered blank and all 27 combinations passed, an
empty 48dp button being exactly 48dp tall. **A geometrically valid empty screen
is not a passing test.**

**Per-app locales.** `AppCompatDelegate.setApplicationLocales` is the correct
mechanism, but `:app` uses `ComponentActivity` and adopting it means taking an
`appcompat` dependency and changing the activity base class. Not to be done
silently — the trade-off gets its own decision before any code moves.

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

`node design/mockups/measure.js` — non-zero exit on any failure.

Per frame, per language: no scroll, nothing painting outside, no clipped text,
every string key resolves, the address has room, no script's ink exceeds its line
box, the legal line is on screen, the header is one row, every control meets its
target, the QR meets its module pitch.

**Current: 27/27 languages × frames, and 84/84 frame × language × state.**

State coverage: idle, checking, found, empty, error, copied-mac, copied-key.

---

## 13. Open decisions

1. **`overline` 11/16 → 11/18** — 1dp of Thai headroom today, 3dp after (§9).
2. **Pre-portal QR** — hide the zone, per §5.3, being the only option consistent
   with "no interim QR".
3. **`appcompat` for per-app locales** — dependency and base-class change (§10).
