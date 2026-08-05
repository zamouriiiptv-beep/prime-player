# Castivio — what has to be true before a release APK is published

A debug APK is for testing the product. A release APK is a promise to a paying
customer, and Castivio's licensing is the part of it that can go wrong quietly.
This file is the list of things that must exist before one is fit to publish, and
it is deliberately short enough to read in full every time.

> **Today, no release APK is fit to publish.** `Licensing.Production` is bound with
> no `EntitlementSource`, so a release build fails closed: every device reads as
> `ServiceUnavailable(NOT_CONFIGURED)` and the licence screen says so. That is the
> intended behaviour of a build with no authority behind it — not a bug to work
> around, and not a reason to switch it to `Licensing.Development`.

## Debug builds are unaffected

Everything below gates *publishing*. A debug APK gets `Licensing.Development`, a
working seven-day local trial, and the whole app — which is what it is for: putting
a real build on a phone and a television and judging the navigation, the focus, the
typography and the feel long before any of this exists.

## 1. The licensing backend

None of these are client work, and none of them can be faked locally.

- [ ] **Licensing backend deployed**, with a stable host Castivio can pin trust to.
- [ ] **`EntitlementSource` implemented and bound** in
      `data/entitlement/di/EntitlementModule.kt` — one `@Provides`, no domain change.
- [ ] **Trial registration is server-side.** The server remembers the MAC address and
      refuses a second free week. A local trial cannot: clearing app data removes the
      record and it would grant another, which is precisely why
      `LocalEntitlementSource` is unreachable in production.
- [ ] **Annual activation** — purchase receipt redeemed, expiry stated by the server.
- [ ] **Lifetime activation** — same path, no expiry.
- [ ] **Revocation** — the server can withdraw an entitlement, including a lifetime
      one. The client already honours it and never infers one locally.
- [ ] **Recovery flow** — a random, high-entropy, server-issued code, not derived from
      the MAC, stored as a hash and never in the clear. It is the only way back for a
      device after a factory reset, or after an installation-scoped identity loses its
      app data.

## 1b. The contract that has to change before any of section 1 is built

`VerificationRequest` — what the client sends the licence server to ask what this
device is entitled to — carries `macAddress`, `identityVersion`, `provenance`,
the legacy addresses and the cached record. Every one of those identifies the
device. **Not one of them authenticates it.**

- [ ] **A device secret in the verification contract.** Until there is one, any
      party who learns an address can impersonate that device to the licence
      server: read its entitlement, and depending on the endpoint, move it.

This is harmless today only because there is no server to lie to. It stops being
harmless the day one exists, which is the day the rest of section 1 gets built —
so it is a blocker on that work rather than a hardening task after it. The same
gap governs the device key and the QR pairing protocol; see
`design/activation-spec.md` §11.

Recorded during activation UI work and deliberately **not** fixed there: the UI
task has no business inventing an authentication protocol.

## 2. The one test that needs a real device

`VaultKeys` is the only part of `:data:entitlement` no JVM can exercise: Robolectric
has no `AndroidKeyStore`, and neither has a plain JVM.

This section used to say the cipher around it was behind a lambda and fully unit-tested,
and leave it there. That was true and it was not enough. A key held in `AndroidKeyStore`
is created with randomised encryption required, so it **refuses an initialisation vector
supplied by the caller** — and the JVM's own provider accepts one happily. `seal`
generated its own nonce, passed every unit test, and threw
`InvalidAlgorithmParameterException: Caller-provided IV not permitted` on the first
launch of every real device. "The collaborator is behind an interface" is not the same
claim as "the contract that collaborator imposes is held somewhere".

So the contract is now modelled: `KeystoreLikeCipher` is a JCE provider that enforces
what a keystore key enforces — an opaque key, no caller nonce when encrypting, a
required nonce when decrypting — and `FirstLaunchTest` runs the whole startup path
behind it. That is what a JVM can hold. What follows is what it cannot.

- [ ] **Instrumented test on a real device and on an emulator**, covering:
  1. create the key on a device that has never had one
  2. seal a record
  3. **restart the process**, so the key is re-fetched rather than remembered
  4. open the record and get the same bytes back
  5. edit one byte of the ciphertext and confirm it is rejected
- [ ] Run on **API 21 or 22** as well as a modern API level — the two paths through
      `VaultKeys` are different code, and the older one is the untested one.

## 2b. The other test that needs a real device: locale resolution

Castivio ships 37 languages out of 39 resource directories, and six of those
directories are named something other than `values-<code>` — `values-in` for
Indonesian, `values-b+fil` for Filipino, two for Chinese, two for Portuguese.
Each one is a place where a plausible-looking directory can silently never match.

The failure is invisible from the inside. A locale that fails to resolve does not
crash; it falls back to English, and the screen *looks* translated to anyone who
does not read the language it was supposed to be in.

`LocaleResolutionTest` asks for every locale and checks which directory answered,
and it runs on every commit — but under Robolectric, which is a faithful
reimplementation of the resolver rather than the resolver. The number 39 is
therefore a proposal with an argument behind it, not a verified fact.

- [ ] **Sentinel resolution on a real device, at both ends of the range.** Every
      one of the 37 canonical tags asked for, and `locale_sentinel` confirming
      which directory answered. Run on **API 21 or 22** and on a current API
      level: those are different resolvers and the older one is the untested one.
- [ ] **The result decides the mapping.** If verification shows a compatibility
      alias is genuinely required — `values-no`, `values-tl` — it is added, and
      if one is shown unnecessary it stays out. Correct resolution is the
      requirement. **The product invariant is 37 user-visible languages, never a
      number of resource directories.**

Also on a device, and cheap once one is in hand: choose a language, force-stop,
reopen, and confirm it is still in that language with the device set to another.

## 3. Before every release, not just the first

- [ ] `./scripts/check-invariants.sh` passes, including the licensing invariant that
      keeps `LocalEntitlementSource` out of everything except
      `Licensing.Development`.
- [ ] The unit suites pass — pure modules and Android modules both.
- [ ] `Licensing.Production` is what a release build actually gets. The choice is made
      once, behind `BuildConfig.DEBUG`, and the invariant script fails the build if
      that check disappears.
- [ ] The legal disclaimer is present and comes from localisation resources, not from
      a string literal.
- [x] **The legal text is real wording and not a bracketed placeholder.** Done,
      and it is a page rather than a footer. `LegalScreen` carries eight
      sections — About, Licence scope, Content responsibility, Copyright,
      Privacy, Refund policy, Support, Acceptance of terms — in all 39 bundles,
      reached from a link in the licence screen's footer.
      `check-invariants.sh` now fails the build on any string that opens with a
      bracket or announces itself as unwritten.

      **Two sections need a lawyer's eye before submission: Privacy and Refund
      policy.** Nothing in them is invented — they state what the application
      does, which is nothing with accounts and nothing with payments — but those
      are the two that bind a business rather than describe a binary.

      Why it is not drawn on the licence screen: the content responsibility
      clause alone renders as four lines of `bodySmall` on every frame, 27dp more
      than the whole band's margin. Reproduce with
      `node measure.js --file licence.html`.
- [ ] **No debug entry point survives the release build.** `DebugEntry` composes
      nothing, `LicenceRoute` ignores `forcedState`, and R8 removes both because
      the constant is false at compile time. `check-invariants.sh` fails the build
      if either file loses its `BuildConfig.DEBUG` check.
- [ ] **No price is shown to somebody who has already paid.** Lifetime, an active
      annual licence, and a licence that merely has not been verified all draw no
      plan cards. `LicenceViewTest` and `LicencePolishTest` hold this.
- [ ] **The startup sound behaves.** Once per process, never on a restore, nothing
      on silent or vibrate or with the media volume at zero, and loaded off the
      first frame's path. `StartupSoundTest` pins all five rules. The asset is
      generated by `tools/startup-sound.py`, so it is Castivio's to ship — there is
      no third-party audio licence to clear.
- [ ] **Exit confirmation on both form factors.** Back from the root shows it, back
      inside the app does not, back while it is open closes only the dialog, and
      focus starts on Cancel. `BackPolicyTest` holds the rule; the dialog is
      `CastivioDialog`, shared with the legal notice through one panel.
- [ ] **The bundled typefaces' licences ship with the app.** Castivio embeds Inter and
      IBM Plex Sans Arabic, both SIL OFL 1.1, which permits commercial bundling only
      if the licence text travels with the software. Both files are in
      `core/design/licenses/`; the About screen has to surface them. Shipping without
      it is a licence breach, not a missing polish item.

## Why it is a type and not a flag

The mistake this whole arrangement exists to prevent is a release APK that licenses
itself — every install entitled, forever, on its own say-so. A boolean is one wrong
`!` away from that, so there isn't one. `Licensing.Production` has nowhere to put a
`TrialGrantor`; it is not that it refuses one, it is that the shape does not admit
one, and no edit to the wiring can smuggle one in.
