# Licence — UX/UI specification

The screen that answers one question: **may this device be used, and if not, what
now?**

Design only. Nothing here is implemented. This document exists to be argued with
before a line of Compose is written, in the same way `activation-spec.md` was.

- Sibling contract: `design/activation-spec.md` (approved, frozen at `12127d0`)
- Domain contract this renders: `domain/entitlement/` — already written and tested
- Mockup: `design/mockups/licence.html` — **measured, 168/168**
- Verification: `node measure.js --file licence.html [--state all]`

---

## 1. What this screen is, and what it is not

**It is a gate and a receipt.** `startDestination()` sends a device here when its
entitlement does not allow use, and Settings will link here when it does. Those
are the only two ways in.

**It is not a shop.** It does not take money, on any platform, in phase 1. It
presents the plans and hands the user to the Castivio portal, which owns
authentication, payment, licence creation and MAC binding. §7 is the whole of it.

**It is not the Add Subscription screen.** Castivio's licence is what the user
buys from us, per device, and it is answered by our licence server. A provider
subscription is bought from a third party and answered by theirs. The two are
deliberately separate systems in the domain layer, and the copy on this screen
must never let them be read as one — a user who cancels the wrong thing because
two screens used the same word has been failed by the writing, not by themselves.

The word **subscription** belongs to the provider. On this screen Castivio sells
a **licence**. That distinction is load-bearing and is checked in §14.

---

## 2. The states, from the type that already exists

This screen does not invent a state model. `EntitlementState` is a sealed type in
`:domain` with nine cases, every one of which can reach a user, and the screen's
job is to have a sentence for each. Inventing a tenth, or collapsing two that
mean different things, is the defect this section exists to prevent.

| `EntitlementState` | `allowsUse` | Screen state | Reached how |
|---|---|---|---|
| `TrialActive(expiresAt, daysRemaining)` | ✅ | **Trial** | Settings only |
| `AnnualActive(expiresAt, daysRemaining)` | ✅ | **Activated** | Settings only |
| `Lifetime` | ✅ | **Activated** | Settings only |
| `TrialExpired` | ❌ | **Expired** | Gate |
| `AnnualExpired` | ❌ | **Expired** | Gate |
| `Unknown` | ❌ | **Not established** | Gate |
| `VerificationUnavailable(plan, expiresAt, graceEnded)` | ❌ | **Verification required** | Gate |
| `ServiceUnavailable(NOT_CONFIGURED \| STORAGE_UNREADABLE)` | ❌ | **Unavailable** | Gate |
| `Revoked(revokedAt)` | ❌ | **Revoked** | Gate |

Plus two states of the screen itself rather than of the entitlement:

| State | Meaning |
|---|---|
| **Loading** | The sealed record is being read. Under 100ms on a phone, seconds on a cold stick. |
| **Working** | A redemption or a refresh is in flight. |

### 2.1 Why "Trial" and "Activated" are Settings-only

Because the gate never routes an allowed device here. That is not an oversight to
be corrected in the UI — `startDestination` is nine lines of tested pure code and
it is right. It means:

- The **Trial** and **Activated** states are only ever seen by a user who went
  looking, from Settings.
- The screen therefore needs a **back affordance in those states and not in the
  others**, and §11 treats that as a state-dependent contract rather than a
  global one.

A screen that showed "3 days left" as a blocking gate would be lying about being
blocked, and a screen with no way out of the Activated state would be a trap.

### 2.2 The state diagram

```
                    ┌──────────┐
                    │ Loading  │ ─────────── entitlement read
                    └────┬─────┘
                         │
         ┌───────────────┼───────────────────────────┐
         │ allowsUse     │ !allowsUse                │ fault
         ▼               ▼                           ▼
   ┌───────────┐   ┌──────────────┐          ┌───────────────┐
   │ Activated │   │   Expired    │          │  Unavailable  │
   │  Trial    │   │ Not estab.   │          │   Revoked     │
   └─────┬─────┘   │ Verification │          └───────┬───────┘
         │         └──────┬───────┘                  │
         │ back           │ choose a plan            │ no plan offered
         ▼                ▼                          ▼
     (Settings)     ┌───────────┐              ┌──────────┐
                    │  Working  │              │ Retry /  │
                    └─────┬─────┘              │ Support  │
              ┌───────────┴──────────┐         └──────────┘
              ▼                      ▼
      ┌───────────────┐      ┌──────────────┐
      │   Activated   │      │  Error       │──── back to the state it came from
      └───────────────┘      └──────────────┘
```

**Working never becomes a fourth destination.** It replaces the field band of
whichever state started it, exactly as `ImportingScreen` does on Activation.
Pressing back cancels it and returns to that state with nothing lost. A fifth
step in a stack would get that wrong, and it is the same argument that settled it
on the sibling screen.

---

## 3. Layout — the same three bands

No experimentation. This screen is the Add Subscription screen's sibling and must
read as one. The structure is identical: **header, hairline, field, hairline,
footer**, in a fixed viewport, immersive, full-bleed, with no scroll.

```
┌─ header ──────────────────────────────────────────────────────────┐
│ Castivio licence            · Trial · 3 days left     ⊕ Language  │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│   ⬭ MAC ADDRESS  2F:19:EB:20:44:7C  ⧉      ┌───────────┐          │
│   ⬭ DEVICE KEY   482731  ⧉                 │    QR     │          │
│                                            └───────────┘          │
│   ┌───────────────┐  ┌───────────────┐   Scan the QR code to …    │
│   │ ANNUAL        │  │ LIFETIME      │                            │
│   │ €6  per year  │  │ €15  once…    │                            │
│   └───────────────┘  └───────────────┘                            │
│   · status line (reserved height)                                 │
├───────────────────────────────────────────────────────────────────┤
│         Castivio is only a player and does not provide …          │
└───────────────────────────────────────────────────────────────────┘
```

### 3.1 Why the plans are the field, and the action is the plan

The requested hierarchy is status → trial → plans → action. Two of those four
collapse, and the collapse is the design:

**The plan card *is* the button.** There is no select-then-continue. On a
television that removes a whole interaction — no invisible selection state to
communicate, no second press, and the project's standing rule that **focused ≠
selected** becomes trivially satisfiable because nothing is ever selected. On a
phone it removes a step nobody wanted.

**Status lives in the header**, as a chip, in the position and at the weight the
trial chip already occupies on Add Subscription. A user moving between the two
screens finds the same information in the same corner.

That leaves the field band holding exactly one thing — the choice — which is what
the band is for, and it fits. §6 does the arithmetic.

### 3.2 The plan card is a capsule that grew

It is not a new component family. It is the approved capsule idea at a larger
size: `RoundedCornerShape(Radius.xl)` at 24dp, `glassFill`, a `glassBorderSoft`
hairline, no shadow. The identity capsules are pills because they hold one line;
these hold four, so they are rounded rectangles rather than pills — the same
material and the same border, at the shape the content needs.

**Neither plan is marked as recommended.** Locked by product decision: both are
presented at equal weight and the user decides. `selectedFill` and
`selectedBorder` therefore carry only *focus*, which removes an entire class of
confusion between "the one we suggest" and "the one the remote is on" — the two
would have been the same two tokens.

### 3.3 What is deliberately absent

- **No large glass card wrapping the pair.** Same rule as the sibling screen: the
  screen is the surface. The plan cards are containers because a priced choice is
  an object; nothing wraps them.
- **The QR is present, in the sibling's position and at the sibling's size.**
  Castivio is portal-first: the app never takes money and every platform sends
  the user to the portal, so the QR is the fastest route there from a
  television. Putting it anywhere else — or leaving it out — would teach a user
  that the code means something different on this screen.
- **No comparison table.** Two plans differing on one axis do not need a matrix.
- **No countdown timer, no urgency animation, no strikethrough "was €12".** The
  price is the price.

---

## 4. Typography — the existing scale, unchanged

| Element | Token | Why |
|---|---|---|
| Screen title | `headlineMedium` (22/32, Bold) / `headlineLarge` (28/40) on TV | Same as the sibling title, same position |
| Status chip label | `bodyMedium` (14/22, Medium) | Same as the trial chip |
| Status chip count | `bodyMedium` + **SemiBold span on the numeral only** | The rule established on Add Subscription in the final polish pass |
| Plan name | `overline` (11/18, SemiBold, tracked) | Same register as MAC ADDRESS / DEVICE KEY — a label naming the thing below it |
| **Price** | `headlineLarge` (28/40) phone, `displayMedium` (36/44) TV | The anchor of the card, as the MAC address is the anchor of its column |
| Period | `bodySmall` (12.5/20) | Subordinate to the price it qualifies |
| Differentiator | `bodySmall` | One line, never two |
| Status line | `labelMedium` (12/16) | Identical to the sibling's status line |
| Legal | `bodySmall` | Identical |

**No new tokens.** If a size is wanted that the scale does not have, the scale is
wrong and is changed in `:core:design` deliberately — not overridden here.

**The price is not monospace.** `CastivioType.Mono` is for identifiers that get
transcribed, spoken and compared character by character. A price is read, not
copied, and Inter's proportional figures set it better. This is a deliberate
departure from the MAC/key treatment and the reason is that they are different
kinds of number.

**Currency formatting is locale-aware and comes from `PlanOffer`.** `priceMinor`
is an integer in the minor unit and `currency` is ISO 4217; the screen formats
with the interface locale's rules. `€6` in English, `6 €` in French, `٦ €` where
the locale uses Arabic-Indic digits. **No string resource contains a price, a
currency symbol, or a period.** §14 makes that a gate.

---

## 5. Colour

| Meaning | Token | Where |
|---|---|---|
| Trial running | `primaryBrush` | Status chip dot — the same fill as the primary button, as fixed on the sibling |
| Licence active | `success` | Status chip dot and text |
| Expired, not established | `warning` | Status chip and status line |
| Revoked, storage unreadable | `danger` | Status chip and status line |
| Recommended plan | `selectedFill` + `selectedBorder` | Plan card |
| Plan card, resting | `glassFill` + `glassBorderSoft` | Plan card |
| Focus | `focusRing` + `focusGlow` | Every focusable |

**`warning` for expiry, `danger` only for a fault.** A trial that ended is not an
error — nothing broke, the user simply has a decision to make, and painting that
red tells somebody their working app is broken. `danger` is reserved for
`Revoked` and `STORAGE_UNREADABLE`, which are genuinely wrong. This is the same
distinction settled on the sibling screen for "no subscription yet", and it must
stay consistent across the two or it means nothing on either.

**`ServiceUnavailable` is `warning`, not `danger`.** It is our outage, not the
user's fault, and the copy says so.

---

## 6. Spacing and the vertical budget

Same philosophy: per-frame metrics transcribed from a mockup, not one scale
stretched three ways; the band claims what the header and footer leave; and the
budget is arithmetic that a JVM test asserts before anything reaches a device.

### 6.1 The frames

The three that Add Subscription is gated on, unchanged: **873×393** (reference),
**800×360** (tight), **TV 960×540**. Heights are the whole display — the app is
edge-to-edge and this screen will be immersive like its sibling.

### 6.2 The budget, sketched

Reusing the sibling's header (57dp phone / 72dp TV), hairlines (2dp) and footer
(28dp phone / 33dp TV), the field band is **259dp** on the tight frame, **284dp**
on the reference frame and **337dp** on the television.

A plan card needs, on a phone:

| | |
|---|---|
| card padding top | 16 |
| plan name (`overline`) | 18 |
| gap | 4 |
| price (`headlineLarge`) | 40 |
| gap | 2 |
| period (`bodySmall`) | 20 |
| rule + gaps | 17 |
| differentiator (`bodySmall`) | 20 |
| card padding bottom | 16 |
| **total** | **153dp** |

Field content = card 153 + gap 16 + status 20 = **189dp** against a 259dp band on
the tight frame: **70dp spare**, which is more headroom than Add Subscription has
ever had. The screen is simpler than its sibling and the budget shows it.

**A 24dp navigation bar swiped back** leaves 46dp. Comfortable.

These numbers are a sketch and are **not** the contract. The contract is that
`LicenceBudgetTest` computes them the way `ActivationBudgetTest` does — from the
same `Metrics` the screen is built from and the line heights `CastivioType`
declares — and fails if any frame goes negative. The sketch exists to show the
shape fits before anyone draws it.

### 6.3 Card width

The pair is centred and each card takes an equal share, because unlike the MAC
and device key capsules these two are being *compared* — asymmetry would read as
one being more important, and which one is more important is what the
recommendation marker is for.

Minimum card width is set by the longest price plus the longest period string
across the 37 languages, measured in the mockup, not guessed.

---

## 7. How a licence is bought — **decided: portal-first**

**Phase 1, every platform.** Android TV, Google TV, Fire TV, phones, tablets and
sideloads all use the Castivio Activation Portal. The application never processes
a payment. The Licence screen presents the plans and opens the portal; the portal
owns authentication, payment, licence creation, MAC binding and the return.

**Phase 2, if Castivio is listed on Google Play.** Play Billing is added *for Play
builds only*, and `RedemptionCredential` stays the single integration point — the
sealed type already distinguishes `PurchaseReceipt` from `RecoveryCode` precisely
so the source of the proof can vary while the server stays the authority. Nothing
above `redeem()` learns that a second route exists.

### 7.1 What pressing a plan card does

1. The card is pressed. No selection, no confirmation step.
2. The portal opens at `ActivationDestination.URL`, carrying the chosen plan.
3. The screen enters **Working** and says so.
4. The user pays on the portal, which binds the licence to this device's MAC.
5. They return. `EntitlementRepository.refresh()` picks the licence up.

**Steps 2 and 3 are where the design is thin and the implementation must be
careful.** On a phone the portal opens in a browser and the app is backgrounded;
on a television there may be no browser worth using, which is exactly why the QR
is on this screen. The Working state must therefore survive being backgrounded
and must not strand a user who never completes the purchase — a Verify control
appears after a short delay so returning is always possible.

### 7.2 What the plan card must carry to the portal

The plan identifier only. Not the price — the portal is the authority on what
something costs, and a client that posted an amount would be a client that could
be edited to post a different one.

### 7.3 Recovery code — **not in version 1**

Locked. `RedemptionCredential.RecoveryCode` remains in the domain contract
unused. Recorded as a known gap: a user who factory-resets a device today has no
in-app route back to a lifetime licence, and the portal must handle that case
until the screen does.

## 8. Copy, per state

Short. No paragraphs. Every string localised into all 37 languages; none of them
contains a price, a number of days, or a currency symbol.

| State | Chip | Field | Status line |
|---|---|---|---|
| **Loading** | — | skeleton, plan-card shaped | — |
| **Trial** | `Trial · {n} days left` | plans | *Your trial ends in {n} days. Choose a licence any time.* |
| **Activated (annual)** | `Licence active` | plans, current one marked | *Renews {date}.* |
| **Activated (lifetime)** | `Licence active` | *lifetime confirmation, no plans* | — |
| **Expired (trial)** | `Trial ended` | plans | *Your trial has ended. Choose a licence to continue.* |
| **Expired (annual)** | `Licence expired` | plans | *Your licence expired {date}.* |
| **Not established** | `No licence` | plans | *This device has no Castivio licence yet.* |
| **Verification required** | `Verification needed` | plans + Verify | *Castivio hasn't been able to check this licence for {n} days.* |
| **Unavailable** | `Unavailable` | *no plans*, Retry | *Castivio can't check licences right now. This is our problem, not yours.* |
| **Revoked** | `Licence withdrawn` | *no plans*, Support | *This licence was withdrawn. Contact support with your device key.* |
| **Working** | unchanged | spinner replacing the field | *Checking with Castivio…* |
| **Error** | unchanged | plans | *That didn't work. {reason}* |

### 8.1 Three copy rules, each from a mistake already made once

1. **Never say "expired" when the truth is "we couldn't check."**
   `VerificationUnavailable` is reached after a 14-day offline grace, and the
   `EntitlementState` KDoc already insists on this: *"the licence screen says
   'couldn't verify', never 'expired', because those are different facts."* A
   paying customer on a fortnight's holiday must not be told they have no
   licence.

2. **Never say "you have no licence" when the record simply would not open.**
   `STORAGE_UNREADABLE` means a device that *had* one and lost the key to it — a
   keystore reset, a restore onto another handset. Telling that user they never
   paid is a support queue.

3. **Never offer a plan a device cannot buy.** Plans come from
   `PricingConfig.purchasable`, which filters on `available`. A region where
   lifetime is not sold shows one card, and the layout must survive that — see
   §10.

---

## 9. Focus order and TV navigation

Deterministic, and stated here so it is not discovered later:

```
Language  →  Plan 1  →  Plan 2  →  [Verify | Retry]  →  Already have a licence?
```

In the **Activated (lifetime)** state there are no plan cards, and the order is
`Language → Already have a licence?` with nothing between. Back leaves.

**Initial focus** is the first plan card in every state that offers plans, and
the recovery action in states that do not. Never the language control: a user
sent here by the gate has a decision to make, and the remote should start on it.

**Left/right moves between plans; up/down leaves the row.** Two cards side by
side is a horizontal group and a D-pad user will press left and right first.

**The recommendation marker is not focus and not selection.** Three visual
states, never conflated: *recommended* (a permanent property of the offer),
*focused* (where the remote is), *working* (what was pressed). The project's rule
is already **focused ≠ selected**; this screen adds a third and must keep all
three distinguishable without colour alone.

---

## 10. Responsive behaviour

| Frame | Plans | Notes |
|---|---|---|
| 873×393 | side by side | reference |
| 800×360 | side by side | tighter card padding, same structure |
| TV 960×540 | side by side | larger price, `minTvTarget` 56dp |
| One purchasable plan | single card, centred | not stretched to full width |
| Three or more | **not designed.** See below. |

`PricingConfig.plans` is a list, so three plans is expressible in the domain
today. The screen will be built for **one or two** and must *fail loudly* rather
than silently truncate or overflow if a third appears — a test asserting the
rendered count equals `purchasable.size`. Designing for a third now would be
designing for a product decision nobody has made.

**Portrait is not designed.** Neither is the sibling screen; Castivio is
landscape-first and the gate is the same gate. Recorded so it is a decision
rather than an omission.

---

## 11. Back behaviour, per state

| State | Back does |
|---|---|
| Trial, Activated | returns to Settings |
| Expired, Not established, Verification, Unavailable, Revoked | **leaves the app** |
| Working | cancels, returns to the state it started from |
| Error | dismisses, returns to the plans |

The blocked states have nowhere to go but out. That is the honest behaviour for a
gate: there is no screen behind it, and a back press that silently did nothing
would have a television user pressing it harder. On a remote, back is the
most-pressed key, so this is specified rather than inherited.

---

## 12. Accessibility

Same standards as the frozen sibling; nothing relaxed.

- **Every plan card is one focusable node** with one description reading name,
  price and period as a sentence — *"Annual licence, six euros per year,
  recommended"* — not three separate nodes a screen reader walks through
  disconnected.
- **The price is read as money, not as digits.** The formatted currency string,
  not `6` followed by a symbol.
- **The recommendation is in the description**, because a border colour is not
  available to a screen reader and is the first thing lost to colour blindness.
- **Status changes announce** via a polite live region, as the sibling's status
  line does.
- **Targets:** 48dp minimum on touch, **56dp on TV** — `minTvTarget`, not
  `minTouchTarget`, and §17.1 is what happens when the two are confused. The
  measurer checks every focusable against the frame's own floor.
- **Structural RTL**, no positional special-casing. Prices and dates are
  locale-formatted and therefore already correct; the card order mirrors, which
  means the recommended card moves side — correct, and worth stating because it
  will look like a bug to a reviewer reading Arabic for the first time.
- **Every animation level down to none.** No state change depends on motion.

---

## 13. Performance

No new dependency for the screen itself. No blur, no shadow except the one the
plan cards do not have, no GPU effect. The entitlement read is already a
`Flow` the app collects at startup, so the screen subscribes to something that
exists rather than starting work.

**Loading shows a skeleton, not a spinner**, and only after a delay — the
existing `DelayedSpinner` and `Skeleton` components are in the design system.
A flash of spinner for a 40ms keystore read is worse than nothing.

---

## 14. Verification gates, before implementation

Written now so implementation targets them, in the same split the sibling proved
necessary — a Robolectric harness cannot lay text out, so what it may claim is
limited on purpose.

| Gate | Claim | Runs in |
|---|---|---|
| `LicenceFrameTest` | every `EntitlementState` maps to exactly one screen state | plain JVM |
| `LicenceBudgetTest` | band, cards and targets add up on all three frames, insets included | plain JVM |
| `LicenceLayoutTest` | Compose places every mandatory element, none zero | Robolectric |
| `LicencePricingTest` | **no price, currency or period appears in any string resource**; every rendered amount comes from `PlanOffer` | plain JVM |
| `LicenceCopyTest` | `VerificationUnavailable` never renders the word "expired"; `STORAGE_UNREADABLE` never renders "no licence" | plain JVM |
| locale gates | the existing completeness, wrong-script and untranslated-copy checks extend to the new strings automatically | script |
| a device | how it looks | — |

`LicencePricingTest` is the one worth arguing for: `PricingConfig`'s whole purpose
is that "no duration, price or currency appears anywhere else", and that promise
is currently kept by a comment. On a screen that renders prices it should be kept
by a test.

---

## 15. Decisions, resolved

| | Decision | Locked as |
|---|---|---|
| 1 | Purchase route | **Option C** — portal-first everywhere in phase 1, Play Billing for Play builds later, `RedemptionCredential` the only integration point |
| 2 | Reachable from Settings | **Yes** — Settings → Licence, so a user can review a licence after activating |
| 3 | Device identity on screen | **Both**, in the approved capsules; the MAC stays visually dominant |
| 4 | Recommended plan | **No** — equal weight, no commercial bias |
| 5 | Legal line | **Kept, rewritten** for a licence screen; the wording is not invented here and the mockup carries a bracketed placeholder |
| 6 | Recovery code | **Not in version 1** |
| 7 | Plan card is the button | **Approved** — no radio, no selected state, no Continue |

### 15.1 The one thing still outstanding

**The legal copy itself.** The mockup renders
`[legal copy for the licence screen — to be written]` in all nine measured
languages, at the same size and position as the sibling's notice, so the layout
is measured against a real line box. Replacing that placeholder is a legal task,
not a design one, and the sentence that lands must be no taller than two lines on
the 800×360 frame or the budget is re-derived.

## 16. The mockup, and what it measured

`design/mockups/licence.html` exists and is measured by the same harness that
gates the sibling.

```
node measure.js --file licence.html              # 27 frame x language
node measure.js --file licence.html --state all  # 168, x every state
```

**168 of 168 combinations fit. No scroll, no overflow, no clipped text, no
undersized target.**

### 16.1 The measured numbers

| Frame | Field band | Header | Footer | QR plate |
|---|---|---|---|---|
| 873×393 | 813 × 256 | 43 | 30 | 157 |
| 800×360 | 748 × 231 | 41 | 28 | 138 |
| TV 960×540 | 864 × 309 | 56 | 33 | 208 |

Tightest line box across all nine languages: `bodySmall` at 20dp holding 17dp of
Arabic ink — 3dp of headroom, the same margin the sibling runs at. The plate
sizes are the sibling's, unchanged, and the QR carries the same payload, so the
module pitch already gated by `ActivationQrTest` applies unchanged.

### 16.2 What the harness found

Two things, and the second is the reason this step exists.

**The TV capsule cannot be 52dp.** `Sizing.minTvTarget` is 56dp, a D-pad target
may not be smaller, and a 56dp control does not fit inside a 52dp pill. The
capsule is **52dp on a phone and 64dp on a television**, and the copy control is
48/56 to match. Two approved instructions — "56dp, consistent on phone and TV"
and "reduce it by 4–6dp" — could not both hold on a television, and the measurer
found the contradiction before any Kotlin was written. That is what a mockup is
for.

**The same fault already exists on the frozen sibling.** See §17.

### 16.3 The font correction

`activation-mac.html` measures through Noto, which is what Android used to
supply. Castivio now bundles Inter and IBM Plex Sans Arabic, so this mockup loads
those from `core/design/src/main/res/font/` instead.

**That makes the sibling's measurements stale.** They are not wrong by much —
Inter and Noto Sans are both neo-grotesques at similar metrics — but they are
measurements of a face the app no longer uses, and this project has twice been
bitten by exactly that. Re-running the sibling against the shipped faces is a
small, separate task and it is listed in §17.

---

## 17. Two defects this phase discovered in already-frozen work

Recorded here rather than fixed silently, because the Add Subscription screen is
frozen and the standing rule is that it changes only for a real production bug.

### 17.1 The TV copy control is below the D-pad minimum — **a real bug**

`Sizing.minTvTarget` is 56dp. In the final polish pass the copy control was
changed from `m.target` — which is 56dp on the television metric set — to a fixed
`Sizing.minTouchTarget`, which is 48dp on every frame. So on a television the
control a remote must land on is 8dp under the project's own minimum.

It is a regression, it was introduced by the polish pass, and no gate caught it:
`ActivationBudgetTest` asserts `m.target >= Sizing.minTouchTarget`, which is the
phone floor, and never checks the television against the television floor.

**Fixed.** The copy control takes `m.target` again — 48dp on a phone, 56 on a
television — and the capsule became a per-frame metric so it can hold it: 52dp on
a phone, 64 on a television. `CAPSULE` as a single constant is gone, because a
single constant is what made the mistake expressible.

And the gate that should have caught it now does: `ActivationBudgetTest` asserts
each frame against **its own** floor, `minTvTarget` on the television and
`minTouchTarget` on a phone. The re-derived budget:

| frame | band | column | spare | with a 24dp bar |
|---|---|---|---|---|
| 873×393 | 284dp | 230dp | 54dp | 42dp |
| 800×360 | 259dp | 226dp | 33dp | 9dp |
| TV 960×540 | 337dp | 276dp | 61dp | 37dp |

### 17.2 The sibling mockup measured the wrong font — **fixed**

`activation-mac.html` now loads the same `@font-face` block this file does, from
`core/design/src/main/res/font/`, and re-measures **27 of 27** with no scroll and
no overflow. Inter and Noto Sans are close enough in metrics that nothing moved,
which is luck rather than design: the numbers were being taken through a face the
app had stopped rendering, and that is true whether or not it happens to matter.

The composition it draws is still the pre-capsule one, so it is a record of an
older drawing rather than of the shipped screen. The authority for what ships is
the Kotlin and `ActivationBudgetTest`; this mockup is where the *design* is
argued, and it will be redrawn when the capsules are folded back into it.

