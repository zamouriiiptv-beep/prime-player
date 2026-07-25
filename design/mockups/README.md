# Mockups

Every screen gets a mockup before it gets code. These files are the mockups, and
they are checked in for one reason: a review that only exists as an image cannot be
re-rendered when a token changes.

## What is here

| File | Screens |
|---|---|
| `shell-home.html` | Shell (rail, top bar), Home, the rail focused, a section the provider does not carry, phone Home, tablet section, and the state-language reference sheet |

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

The PNGs are deliberately **not** committed: they are five megabytes that can be
regenerated in a second, and a stale image in the repository is worse than none.

## Rules these files follow

- Sizes are dp, matching `Spacing`, `Sizing` and `Radius` in `:core:design`.
- Colours are the real tokens from `Color.kt`. A hex value that is not in the
  palette is a defect in the mockup, not a design decision.
- The safe area on television is 5%, which is `Spacing.tvOverscan` at 1080p.
- Type follows `Type.kt`. The rendering font is whichever grotesque the rendering
  machine has; the app itself uses the platform sans.
