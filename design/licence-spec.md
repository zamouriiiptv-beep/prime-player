# Licence — UX/UI specification

The screen that answers one question: **may this device be used, and if not, what
now?**

Design only. Nothing here is implemented. This document exists to be argued with
before a line of Compose is written, in the same way `activation-spec.md` was.

- Sibling contract: `design/activation-spec.md` (approved, frozen at `12127d0`)
- Domain contract this renders: `domain/entitlement/` — already written and tested
- Mockup: **not yet drawn.** See §16 for why that is deliberate.

---

## 1. What this screen is, and what it is not

**It is a gate and a receipt.** `startDestination()` sends a device here when its
entitlement does not allow use, and Settings will link here when it does. Those
are the only two ways in.

**It is not a shop.** It does not take money. §7 explains what it does instead,
and that is the single largest open decision in this document.

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
│   ┌──────────────────────────┐   ┌──────────────────────────┐     │
│   │  ANNUAL                  │   │  LIFETIME     ·recommended│    │
│   │  €6                      │   │  €15                     │     │
│   │  per year                │   │  once, for this device   │     │
│   │  ─────────────────────── │   │  ─────────────────────── │     │
│   │  Renews every year       │   │  Never expires           │     │
│   └──────────────────────────┘   └──────────────────────────┘     │
│                                                                   │
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

**The recommended plan** is marked with `selectedFill` and `selectedBorder`,
which already exist as tokens and are already what "this one, out of a set" means
elsewhere in Castivio. Not a brighter colour, not a badge with a drop shadow.

### 3.3 What is deliberately absent

- **No large glass card wrapping the pair.** Same rule as the sibling screen: the
  screen is the surface. The plan cards are containers because a priced choice is
  an object; nothing wraps them.
- **No QR by default.** See §7.3 — it is an option, not a default, and it is
  argued rather than assumed.
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

## 7. How a licence is actually bought — **the open decision**

Everything above is design. This is product, and it is not mine to settle.

`EntitlementRepository.redeem(RedemptionCredential)` takes one of two things, and
the contract already spells both out:

- `PurchaseReceipt(token, productId)` — "deliberately opaque and store-agnostic.
  Play Billing is one possible producer of this string and must not become the
  shape of it."
- `RecoveryCode(code)` — a high-entropy code the server issued, for moving an
  entitlement to a device that lost its identity.

There is a standing decision that the app does **not** implement payment
processing. So the screen cannot take money today, and what "choose a plan"
*does* depends entirely on which of these three routes is chosen.

### 7.1 Route A — the web portal, mirroring Add Subscription

Choosing a plan shows the device's identity and sends the user to
`ActivationDestination.URL` to pay there; they return and press a Verify control.

- **For:** one purchase mechanism for the whole product; no store dependency, no
  store commission; identical to the flow the sibling screen already teaches, so
  the user learns it once. The `ActivationDestination` constant, the QR encoder
  and the identity capsules all already exist and would be reused, not rebuilt.
- **Against:** paying on a phone browser to unlock a television is friction, and
  on **Google Play** shipping a digital purchase outside Play Billing is a policy
  violation that can remove the app. If Castivio is ever listed on Play, this
  route alone is not survivable.

### 7.2 Route B — Play Billing

Choosing a plan opens the Play purchase sheet; the resulting token goes to
`redeem(PurchaseReceipt(...))`.

- **For:** compliant, one tap, trusted payment sheet, works on Google TV.
- **Against:** a real dependency and a real commission; does not exist on Fire TV
  or on sideloaded installs, which are named target platforms; and it puts a
  store SDK in a codebase that has kept every platform dependency behind an
  interface so far.

### 7.3 Route C — both, chosen by build

Play Billing where the app came from Play; the portal everywhere else. The domain
contract already anticipates exactly this: `redeem` takes a sealed credential
precisely so the *source* of the proof can vary while the server stays the
authority.

- **For:** the only route that is both compliant and viable on Fire TV and
  sideloads. The `Licensing` sealed type is already the seam for it.
- **Against:** two flows to design, write, translate and test.

**My recommendation: C, with A designed and built first.** A is the one that
works on every platform Castivio targets, it reuses components that already
exist, and it is the one that can be built before a Play listing exists. B is
added behind the same `redeem` call when a listing is real. Designing A first
costs nothing if B follows, because the sealed credential means neither knows
about the other.

**This blocks the mockup**, because it changes what happens after a plan card is
pressed, and therefore what the second state of the field band contains.

### 7.4 "I already paid"

Whichever route wins, the screen needs a restore path — `RecoveryCode` exists in
the contract for exactly this, and a user who factory-resets a stick and loses a
lifetime licence with no way to get it back is a refund and a bad review.

Proposal: a low-emphasis text action in the footer band, *"Already have a
licence?"*, opening a code entry. Low emphasis because it is the rare path; not
absent, because it is the expensive one to get wrong.

---

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
- **Targets:** 48dp minimum on touch, 56dp on TV. A plan card is far larger; the
  recovery text action is the one to watch.
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

## 15. Open decisions

Nothing below is mine to choose. The mockup waits on the first.

1. **Purchase route — A, B or C (§7).** Blocks the mockup. My recommendation is
   C with A first.
2. **Is the licence screen reachable from Settings?** The Trial and Activated
   states only exist if yes. If no, three of the eleven states are dead and the
   spec shrinks.
3. **Does this screen show the device key?** Route A needs the user to identify
   the device on the portal, which argues yes. It also duplicates the sibling
   screen, which argues for showing it only in the second step after a plan is
   chosen.
4. **Is lifetime "recommended"?** §3.2 marks one plan and the marker is a real
   nudge. €15 once against €6 a year pays back in under three years, so lifetime
   is the honest recommendation for a device kept that long — but this is a
   commercial call, and "no recommendation at all" is a legitimate answer.
5. **Does the legal line change?** The sibling's *"Castivio is only a player…"*
   is about content, not money. A screen that sells something may need a
   different or additional line — VAT inclusivity, refund terms, who the seller
   is. This is a legal question, not a design one, and I will not invent an
   answer.
6. **Recovery code, or not, in the first version (§7.4).**

---

## 16. Why there is no mockup in this document

Because `design/mockups/` is measured, not sketched. `measure.js` renders every
frame in every language and fails on overflow, clipping and short touch targets,
and that harness is the reason the sibling screen's numbers are trustworthy.

Building that for a field band whose second state depends on decision 1 would
mean measuring a layout that may not survive the answer. The mockup is the next
step of this same phase, not a later one — and it will take an afternoon once
§15.1 is settled.

**Order from here:** decisions → mockup at three frames → `measure.js` green in
all 37 languages → this document updated with the measured numbers → then, and
only then, Kotlin.
