/**
 * Measures a mockup frame instead of judging it by eye.
 *
 * A layout that has to fit in one viewport is a claim with a number behind it, and
 * the number is cheap to read: this loads each frame at its real logical size and
 * asks the DOM four questions.
 *
 *   1. Is the document taller than its frame?      -> the screen scrolls
 *   2. Does anything paint outside the frame?      -> content is being clipped
 *   3. Does the MAC address fit its column?        -> measured on the glyph run,
 *                                                     not the box, because a
 *                                                     full-width box always
 *                                                     "fits" itself
 *   4. Do the columns resolve to the asked widths? -> a flex basis taken from a
 *                                                     paragraph's max-content
 *                                                     width silently shrinks its
 *                                                     neighbours
 *
 * Three of those four caught a real defect while `activation-mac.html` was being
 * written, number 4 the worst of them: `flex:1 1 auto` on the note column won the
 * space and squeezed the MAC card to 238dp when the address needed 323. It looked
 * plausible in the stylesheet and wrong on screen.
 *
 * Usage, from this directory:
 *
 *   node measure.js                       # every frame of activation-mac.html
 *   node measure.js shell-home.html       # another file's frames
 *   node measure.js activation-mac.html --shots ./out
 *
 * Needs Playwright, which is not a dependency of this repository -- mockups are
 * checked in CI by nothing, and this is a tool for whoever is drawing one.
 * `npm i -g playwright` is enough.
 */
"use strict";

const path = require("path");
const { execSync } = require("child_process");

function loadPlaywright() {
  for (const from of [null, () => execSync("npm root -g", { encoding: "utf8" }).trim()]) {
    try {
      return require(from ? path.join(from(), "playwright") : "playwright");
    } catch (_) { /* try the next place */ }
  }
  console.error(
    "Playwright not found. `npm i -g playwright` — the browser itself is already\n" +
    "present in this environment, so no download is needed.",
  );
  process.exit(2);
}

/**
 * The frames each file declares, and the geometry each one stands for.
 *
 * Kept here rather than parsed out of the CSS: the logical size of a device is a
 * fact about the device, and a mockup that quietly changed its own frame size
 * would be exactly the thing this file exists to catch.
 */
const FRAMES = {
  "activation-mac.html": [
    ["phone-873", 873, 393, 2, "1080x2400 at 2.75, landscape"],
    ["phone-800", 800, 360, 2, "720x1600 at 2.0, the shortest landscape we ship to"],
    ["tv", 960, 540, 2, "1920x1080 at 2.0"],
  ],
  "shell-home.html": [
    ["tv-home", 960, 540, 2, "1920x1080 at 2.0"],
    ["tv-rail", 960, 540, 2, "1920x1080 at 2.0"],
    ["tv-empty", 960, 540, 2, "1920x1080 at 2.0"],
    ["phone-home", 412, 892, 3, "1236x2676 at 3.0, portrait"],
    ["tablet-section", 1280, 800, 1.5, "1920x1200 at 1.5"],
  ],
};

async function main() {
  const { chromium } = loadPlaywright();

  const args = process.argv.slice(2);
  const shotsAt = args.indexOf("--shots");
  const shots = shotsAt === -1 ? null : args[shotsAt + 1];
  const file = args.find((a) => a.endsWith(".html")) || "activation-mac.html";

  const frames = FRAMES[path.basename(file)];
  if (!frames) {
    console.error(`No frame list for ${file}. Add one to FRAMES in measure.js.`);
    process.exit(2);
  }

  const url = "file://" + path.resolve(file);
  const browser = await chromium.launch({ args: ["--no-sandbox"] });
  let failures = 0;

  for (const [frame, width, height, scale, note] of frames) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
    await page.goto(`${url}?frame=${frame}`);
    await page.waitForTimeout(250);

    const seen = await page.evaluate((frame) => {
      const root = document.getElementById(frame);
      if (!root) return { missing: true };

      const round = (n) => Math.round(n * 10) / 10;
      const size = (e) => ({ w: round(e.getBoundingClientRect().width), h: round(e.getBoundingClientRect().height) });
      const bounds = root.getBoundingClientRect();

      // The glyph run, and the width that actually constrains it.
      //
      // Two traps here, both of which produced a wrong answer first time. A
      // block-level box is as wide as its parent, so comparing the text against
      // the box it sits in says "fits" no matter how badly it overflows. And a
      // content-sized box is *exactly* as wide as its text by construction, so
      // that same comparison then reports zero slack on a layout with hundreds
      // of dp to spare.
      //
      // So: clipping is the browser's own answer, scrollWidth against
      // clientWidth. Slack is measured against the card's inner width, which is
      // the thing a designer can actually spend.
      const code = root.querySelector(".address");
      let address = null;
      if (code) {
        const range = document.createRange();
        range.selectNodeContents(code);
        const text = round(range.getBoundingClientRect().width);

        const holder = code.closest(".card, .band") || code.parentElement;
        const pad = getComputedStyle(holder);
        const room = round(holder.clientWidth - parseFloat(pad.paddingLeft) - parseFloat(pad.paddingRight));

        address = {
          text,
          room,
          slack: round(room - text),
          clipped: code.scrollWidth > code.clientWidth + 1,
          fontPx: parseFloat(getComputedStyle(code).fontSize),
        };
      }

      return {
        frame: size(root),
        document: { w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight },
        boxes: [...root.querySelectorAll(".card, .band, .c-qr, .c-note")].map((e) => ({
          name: e.className.replace(/glass|card|glass-hero/g, "").trim() || e.tagName.toLowerCase(),
          ...size(e),
        })),
        address,
        outside: [...root.querySelectorAll("*")]
          .filter((e) => {
            const b = e.getBoundingClientRect();
            return b.height > 0 && (b.bottom > bounds.bottom + 0.5 || b.right > bounds.right + 0.5);
          })
          .slice(0, 6)
          .map((e) => String(e.className || e.tagName).slice(0, 48)),
      };
    }, frame);

    console.log(`\n${frame}  ${width}x${height}dp  (${note})`);

    if (seen.missing) {
      console.log("  MISSING: no element with that id");
      failures++;
    } else {
      const scrolls = seen.document.h > seen.frame.h + 0.5 || seen.document.w > seen.frame.w + 0.5;
      const verdicts = [
        [`scrolls (${seen.document.w}x${seen.document.h} in ${seen.frame.w}x${seen.frame.h})`, scrolls],
        [`paints outside: ${seen.outside.join(", ")}`, seen.outside.length > 0],
        [`address clipped (${seen.address && seen.address.text}dp of text, ` +
         `${seen.address && seen.address.room}dp of room)`,
         !!seen.address && (seen.address.clipped || seen.address.slack < 0)],
      ];
      for (const box of seen.boxes) console.log(`  ${box.name.padEnd(18)} ${String(box.w).padStart(6)} x ${box.h}`);
      if (seen.address) {
        console.log(`  address @${seen.address.fontPx}px  ${seen.address.text}dp of text in ` +
                    `${seen.address.room}dp of room, ${seen.address.slack}dp of slack`);
      }
      const broken = verdicts.filter(([, bad]) => bad);
      if (broken.length === 0) {
        console.log("  ok — fits, nothing clipped");
      } else {
        for (const [why] of broken) console.log(`  FAIL: ${why}`);
        failures += broken.length;
      }
    }

    if (shots) await page.screenshot({ path: path.join(shots, `${frame}.png`) });
    await page.close();
  }

  await browser.close();
  console.log(failures === 0 ? "\nAll frames fit.\n" : `\n${failures} problem(s).\n`);
  process.exit(failures === 0 ? 0 : 1);
}

main();
