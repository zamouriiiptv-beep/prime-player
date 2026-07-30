# Mockups

Every screen gets a mockup before it gets code. These files are the mockups, and
they are checked in for one reason: a review that only exists as an image cannot be
re-rendered when a token changes.

## What is here

| File | Screens |
|---|---|
| `shell-home.html` | Shell (rail, top bar), Home, the rail focused, a section the provider does not carry, phone Home, tablet section, and the state-language reference sheet |
| `activation-mac.html` | The first screen, where a subscription is added — phone landscape 873x393 and 800x360, television 960x540, in nine stress languages |

## Rendering

Each file renders one frame at a time, chosen by `?frame=`, at exactly the logical
size of the device. Render at the device's scale factor and the PNG is pixel-accurate
to what Compose lays out — a 1080p television is 960×540 dp at 2×.

```sh
CHROME=/path/to/chrome            # any Chromium; headless_shell avoids window chrome
render() {  # frame  width  height  scale  out
  "$CHROME" --headless --no-sandbox --disable-gpu --hide-scrollbars \
    --force-device-scale-factor="$4" --window-size="$2,$3" \
    --screenshot="$5" --virtual-time-budget=1500 \
    "file://$PWD/shell-home.html?frame=$1"
}

render tv-home         960  540 2    tv-home.png          # 1920x1080
render tv-rail         960  540 2    tv-rail.png
render tv-empty        960  540 2    tv-empty-section.png
render phone-home      412  892 3    phone-home.png
render tablet-section 1280  800 1.5  tablet-movies.png
render states          960 1000 2    state-language.png   # a reference sheet, not a device
```

`activation-mac.html` takes the same `?frame=`, plus `?lang=` for the stress
languages, and `&spec=1` overlays the measures and the safe area:

```sh
render() { ... "file://$PWD/activation-mac.html?frame=$1"; }   # same helper

render phone-873  873 393 2   mac-phone-873.png    # 1080x2400 at 2.75, landscape
render phone-800  800 360 2   mac-phone-800.png    # 720x1600 at 2.0, the shortest
render tv         960 540 2   mac-tv.png           # 1920x1080 at 2.0
```

## Measuring

A layout that has to fit is not a layout to eyeball, so `measure.js` reads the
element boxes out of the DOM and checks eight things on every frame **in every
stress language**: the document is no bigger than its frame, nothing paints
outside it, no text overflows its box, the address fits the row that holds it,
the standing notice is still on screen, the header is still one row, every
control still meets its touch or D-pad minimum, and the QR still has the module
pitch a camera needs.

```sh
npm i -g playwright          # the browser is already here; only the driver is missing
node measure.js              # every frame x every language
node measure.js --lang en,de --frame tv
node measure.js --shots ./out
```

Exit code is non-zero when any frame in any language fails, so it works as a gate.

### The stress languages

English alone cannot check a claim about fitting. Nine languages are measured,
each for a different reason: `en` baseline, `ar` right-to-left and bidi, `de`
and `fi` expansion, `th` and `hi` and `bn` line boxes taller than Latin's, `ja`
and `zh` line breaking without spaces.

This needs the Noto faces Android ships — Noto Sans, and the Arabic, Thai and
Devanagari families — or Thai and Devanagari fall back to whatever the machine
has and their line boxes, the thing being measured, stop being the device's.
`measure.js` says so if they are missing. CJK falls back to WenQuanYi here,
which is adequate because a CJK glyph is one em wide by definition.

Three of those four checks caught a real defect while `activation-mac.html` was
being drawn. The worst was `flex:1 1 auto` on the note column: a flex basis taken
from a paragraph's max-content width wins the space and shrinks its neighbours,
which left the MAC card at 238dp when the address needed 323 — plausible in the
stylesheet, clipped on screen.

Four of the checks were wrong before they were right, and the pattern is worth
knowing because it recurs:

- **The address was measured against the box it sits in.** A block-level box is
  as wide as its parent, so it always "fits" however badly it overflows; a
  content-sized box is exactly as wide as its text, so it always reports zero
  slack. Room is now the row's content width less the label, the copy control
  and the gaps — what the address could actually grow into.
- **Line height was measured against `line-height:normal`.** That is the font's
  *recommended* spacing, and Noto Sans Arabic recommends 46dp for a 22sp string,
  so every Arabic line "failed" while rendering perfectly. The yardstick is the
  glyphs' ink, from Canvas TextMetrics.
- **The worst-case summary filtered on a value that could never match.** The
  probe returned a key called `frame` holding the viewport box, and the caller
  spread it over the `frame` naming the device. The summary was dead for three
  runs and said nothing rather than saying something wrong, which is why it took
  three runs to notice.

A check that fails a healthy layout is worse than no check, and a check that
silently measures nothing is worse than both.

The PNGs are deliberately **not** committed: they are five megabytes that can be
regenerated in a second, and a stale image in the repository is worse than none.

## Rules these files follow

- Sizes are dp, matching `Spacing`, `Sizing` and `Radius` in `:core:design`.
- Colours are the real tokens from `Color.kt`. A hex value that is not in the
  palette is a defect in the mockup, not a design decision.
- The safe area on television is 5%, which is `Spacing.tvOverscan` at 1080p.
- Type follows `Type.kt`. The rendering font is whichever grotesque the rendering
  machine has; the app itself uses the platform sans.
