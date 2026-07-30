# Mockups

Every screen gets a mockup before it gets code. These files are the mockups, and
they are checked in for one reason: a review that only exists as an image cannot be
re-rendered when a token changes.

## What is here

| File | Screens |
|---|---|
| `shell-home.html` | Shell (rail, top bar), Home, the rail focused, a section the provider does not carry, phone Home, tablet section, and the state-language reference sheet |
| `activation-mac.html` | MAC activation — the screen a first launch opens on — at phone landscape 873x393 and 800x360, and television 960x540 |

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

`activation-mac.html` takes the same `?frame=`, and `&spec=1` overlays the measures
and the safe area on any of its frames:

```sh
render() { ... "file://$PWD/activation-mac.html?frame=$1"; }   # same helper

render phone-873  873 393 2   mac-phone-873.png    # 1080x2400 at 2.75, landscape
render phone-800  800 360 2   mac-phone-800.png    # 720x1600 at 2.0, the shortest
render tv         960 540 2   mac-tv.png           # 1920x1080 at 2.0
```

## Measuring

A layout that has to fit is not a layout to eyeball, so `measure.js` reads the
element boxes out of the DOM and answers four questions per frame: is the
document taller than its frame, does anything paint outside it, does the address
fit its column, and do the columns resolve to the widths the stylesheet asks for.

```sh
npm i -g playwright          # the browser is already here; only the driver is missing
node measure.js              # every frame of activation-mac.html
node measure.js --shots ./out
```

Exit code is non-zero when a frame does not fit, so it works as a gate.

Three of those four checks caught a real defect while `activation-mac.html` was
being drawn. The worst was `flex:1 1 auto` on the note column: a flex basis taken
from a paragraph's max-content width wins the space and shrinks its neighbours,
which left the MAC card at 238dp when the address needed 323 — plausible in the
stylesheet, clipped on screen.

Two of the checks were wrong before they were right, both in the same way: they
compared the address against the box it sits in rather than against the width
that constrains it. A block-level box is as wide as its parent, so it always
"fits"; a content-sized box is exactly as wide as its text, so it always reports
zero slack. Clipping is now the browser's own answer and slack is measured
against the card's inner width. A check that fails a healthy layout is worse than
no check.

The PNGs are deliberately **not** committed: they are five megabytes that can be
regenerated in a second, and a stale image in the repository is worse than none.

## Rules these files follow

- Sizes are dp, matching `Spacing`, `Sizing` and `Radius` in `:core:design`.
- Colours are the real tokens from `Color.kt`. A hex value that is not in the
  palette is a defect in the mockup, not a design decision.
- The safe area on television is 5%, which is `Spacing.tvOverscan` at 1080p.
- Type follows `Type.kt`. The rendering font is whichever grotesque the rendering
  machine has; the app itself uses the platform sans.
