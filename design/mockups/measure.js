/**
 * Measures a mockup frame instead of judging it by eye, in every language that
 * can break it.
 *
 * A layout that has to fit in one viewport is a claim with a number behind it,
 * and English alone cannot check that claim: German and Finnish expand by a
 * third, Thai and Devanagari have line boxes taller than Latin's, Japanese and
 * Chinese break lines without spaces, and Arabic runs the other way. So every
 * frame is measured in all of them and the worst case is the answer.
 *
 * The checks, one per thing that has actually gone wrong here:
 *
 *   scroll         document no taller or wider than its frame
 *   outside        nothing painting past the frame's edge
 *   clipped        no element whose text overflows its own box
 *   mac            the address fits its column, measured on the glyph run
 *   footer         the standing notice still on screen and not empty
 *   header         still one row, not wrapped to two
 *   targets        every button still at its minimum touch or D-pad size
 *   qr             the code still large enough for its module pitch
 *
 * Usage, from this directory:
 *
 *   node measure.js                       # every frame x every language
 *   node measure.js --lang en,de          # a subset
 *   node measure.js --frame tv
 *   node measure.js --state all           # x every transient state as well
 *   node measure.js --shots ./out         # PNGs while measuring
 *   node measure.js --file shell-home.html
 *
 * Exit code is non-zero when any frame in any language fails, so it gates.
 *
 * Needs Playwright, which is not a dependency of this repository -- nothing in
 * CI renders a mockup, and this is a tool for whoever is drawing one.
 * `npm i -g playwright` is enough; the browser is already present.
 *
 * It also needs one Noto face per script in the shipping set, or that script is
 * measured through a fallback that has none of its glyphs -- and the line box,
 * the thing being measured, stops being the device's. The script exits rather
 * than run: apt-get install fonts-noto-core fonts-noto-cjk.
 */
"use strict";

const path = require("path");
const { execSync } = require("child_process");

function loadPlaywright() {
  for (const where of [null, () => execSync("npm root -g", { encoding: "utf8" }).trim()]) {
    try {
      return require(where ? path.join(where(), "playwright") : "playwright");
    } catch (_) { /* next */ }
  }
  console.error("Playwright not found. `npm i -g playwright`.");
  process.exit(2);
}

/** The logical size of each device, which is a fact about the device. */
const FILES = {
  "activation-mac.html": {
    frames: [
      ["phone-873", 873, 393, 2, "1080x2400 @2.75, landscape"],
      ["phone-800", 800, 360, 2, "720x1600 @2.00, the shortest we ship to"],
      ["tv", 960, 540, 2, "1920x1080 @2.00"],
    ],
    langs: ["en", "ar", "de", "fi", "th", "hi", "bn", "ja", "zh"],
    requires: ["header", "footer", "mac", "qr"],
    // Every transient state the screen can be in. They are measured too because
    // each one changes the layout: a spinner and a longer verb widen the refresh
    // button, a status sentence appears under the actions and can wrap, and a
    // focus ring is drawn outside the control it belongs to.
    states: ["copied-mac", "copied-key", "checking", "found", "none", "error", "focus-copy"],
    // The languages the state matrix runs in: the baseline, the mirror, the
    // widest and the tallest. Nine languages times eight states is a slow run
    // that says nothing the worst four do not.
    stateLangs: ["en", "ar", "de", "th"],
  },
  "language-picker.html": {
    frames: [
      ["phone-873", 873, 393, 2, "1080x2400 @2.75, landscape"],
      ["tv", 960, 540, 2, "1920x1080 @2.00"],
    ],
    // The activation nine, plus the three the picker adds: a second RTL, which
    // the picker is the first screen to contain more than one of; Hangul, whose
    // metrics no other language in the set stands for; and Cyrillic.
    langs: ["en", "ar", "de", "fi", "th", "hi", "bn", "ja", "zh", "fa", "ko", "ru"],
    // A picker has no address, no code and no standing notice. Declaring what a
    // file has stops the probe inventing a failure out of a structure that was
    // never meant to be there -- and, more usefully, stops it staying silent
    // about one that was.
    requires: ["header", "scroller"],
  },
  "shell-home.html": {
    frames: [
      ["tv-home", 960, 540, 2, "1920x1080 @2.00"],
      ["tv-rail", 960, 540, 2, "1920x1080 @2.00"],
      ["tv-empty", 960, 540, 2, "1920x1080 @2.00"],
      ["phone-home", 412, 892, 3, "1236x2676 @3.0, portrait"],
      ["tablet-section", 1280, 800, 1.5, "1920x1200 @1.5"],
    ],
    langs: ["en"],
    requires: [],
  },
};

/** Why each language is in the list, printed so a failure explains itself. */
const WHY = {
  en: "baseline", ar: "RTL + bidi", de: "expansion", fi: "long words",
  th: "line height", hi: "Devanagari line height", bn: "Bengali conjuncts",
  ja: "CJK line breaking",
  zh: "CJK line breaking",
  fa: "second RTL, Perso-Arabic", ko: "Hangul metrics", ru: "Cyrillic",
};

const MIN = { touch: 48, tv: 56, qrPitchDp: 3.0, qrModules: 21 };

/**
 * One face per script in the shipping set, and a hard stop when one is absent.
 *
 * This used to be a warning over four families, and Bengali and the CJK faces
 * were not among them. Both were measured anyway: `bn`, `ja` and `zh` all
 * reported comfortable headroom, against a fallback face that does not contain
 * their glyphs. A measurement taken through the wrong font is not a weaker
 * measurement, it is a different one, and it passed while saying nothing.
 *
 * So it exits instead of warning. A run that cannot measure what it claims to
 * measure has no result to report.
 */
function warnFonts() {
  const need = {
    "Noto Sans": "Latin, Cyrillic, Greek",
    "Noto Sans Arabic": "Arabic, Persian, Urdu",
    "Noto Sans Thai": "Thai",
    "Noto Sans Devanagari": "Hindi",
    "Noto Sans Bengali": "Bengali",
    "Noto Sans CJK SC": "Simplified Chinese",
    "Noto Sans CJK TC": "Traditional Chinese",
    "Noto Sans CJK JP": "Japanese",
    "Noto Sans CJK KR": "Korean",
  };
  let have = "";
  try { have = execSync("fc-list : family", { encoding: "utf8" }); } catch (_) {
    console.error("  ! fontconfig unavailable; cannot confirm the faces being measured.");
    process.exit(2);
  }
  const missing = Object.keys(need).filter((f) => !have.includes(f));
  if (missing.length) {
    console.error("\n  ! missing font(s), so these scripts would be measured through a");
    console.error("    fallback face and the numbers would not be the device's:\n");
    for (const f of missing) console.error(`      ${f.padEnd(24)} ${need[f]}`);
    console.error("\n    apt-get install fonts-noto-core fonts-noto-cjk\n");
    process.exit(2);
  }
}

/** Runs inside the page. Everything measured, nothing assumed. */
function probe({ frame, isTv, requires }) {
  const root = document.getElementById(frame);
  if (!root) return { missing: true };

  // What this file is claiming to contain. A screen with no address is not a
  // screen with a broken address, and a probe that cannot tell the difference
  // either invents failures on one file or -- far worse -- goes quiet on
  // another. Declared per file, so an element that vanishes from a file that
  // requires it is still a failure.
  const needs = (name) => requires.includes(name);

  const r1 = (n) => Math.round(n * 10) / 10;
  const box = (e) => {
    const b = e.getBoundingClientRect();
    return { w: r1(b.width), h: r1(b.height) };
  };
  const bounds = root.getBoundingClientRect();
  const fails = [];

  // --- scroll: the whole point of the exercise -----------------------------
  const doc = { w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight };
  if (doc.h > bounds.height + 0.5) fails.push(`scrolls vertically (${doc.h} > ${r1(bounds.height)})`);
  if (doc.w > bounds.width + 0.5) fails.push(`scrolls horizontally (${doc.w} > ${r1(bounds.width)})`);

  // --- outside: content pushed past the frame ------------------------------
  // Rows below the fold of a scrolling list are outside the frame by design,
  // so they are measured against their scroller instead, below. Everything
  // else, including the scroller itself, is measured against the frame.
  const outside = [...root.querySelectorAll("*")].filter((e) => {
    const b = e.getBoundingClientRect();
    if (b.height <= 0 || b.width <= 0) return false;
    if (e.closest("[data-scroller]") && !e.hasAttribute("data-scroller")) return false;
    return b.bottom > bounds.bottom + 0.5 || b.top < bounds.top - 0.5 ||
           b.right > bounds.right + 0.5 || b.left < bounds.left - 0.5;
  }).map((e) => String(e.className || e.tagName).slice(0, 40));
  if (outside.length) fails.push(`paints outside: ${[...new Set(outside)].slice(0, 5).join(", ")}`);

  // --- scrollers: vertical overflow is the feature, horizontal is the bug ---
  // A list that scrolls sideways as well has a column too wide for it, which on
  // a D-pad means a row the remote can reach and cannot read.
  const scrollers = [...root.querySelectorAll("[data-scroller]")].map((list) => {
    const lb = list.getBoundingClientRect();
    const rows = [...list.children];
    const wide = rows.filter((e) => {
      const b = e.getBoundingClientRect();
      return b.left < lb.left - 0.5 || b.right > lb.right + 0.5;
    });
    if (wide.length) fails.push(`${wide.length} row(s) wider than the list they are in`);
    if (list.scrollWidth > list.clientWidth + 1) {
      fails.push(`list scrolls horizontally (${list.scrollWidth} > ${list.clientWidth})`);
    }
    // How much of the list a viewer can see at once -- the number the argument
    // about whether this needs a search field rests on, so it is counted rather
    // than divided. Dividing the list's height by a row's height was tried and
    // overstated a television by four rows, because it spends the gaps and the
    // padding as though they were list.
    const inside = rows.filter((e) => {
      const b = e.getBoundingClientRect();
      return b.top >= lb.top - 0.5 && b.bottom <= lb.bottom + 0.5;
    });
    const first = rows.length ? rows[0].getBoundingClientRect() : null;
    const perRow = first
      ? rows.filter((e) => Math.abs(e.getBoundingClientRect().top - first.top) < 1).length
      : 0;
    return {
      rows: rows.length, cols: perRow, rowH: r1(first ? first.height : 0),
      visible: inside.length,
      screens: inside.length ? r1(rows.length / inside.length) : 0,
    };
  });
  if (needs("scroller") && !scrollers.length) fails.push("no scrolling list");

  // --- clipped: text wider or taller than the box holding it --------------
  // The browser's own answer, which is the only one that survives a font swap.
  const clipped = [...root.querySelectorAll("h1, p, span, div")].filter((e) => {
    if (!e.textContent.trim()) return false;
    if (getComputedStyle(e).overflow === "visible" && e.scrollHeight > e.clientHeight + 1 &&
        getComputedStyle(e).whiteSpace === "nowrap") return true;
    return e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).whiteSpace === "nowrap";
  }).map((e) => `${String(e.className || e.tagName).slice(0, 28)}(${e.scrollWidth}>${e.clientWidth})`);
  if (clipped.length) fails.push(`clipped: ${clipped.slice(0, 4).join(", ")}`);

  // --- strings: a key that resolves to nothing renders an empty control ----
  // This exists because it happened. The refresh button was pointed at a key
  // the table did not have, rendered with no text at all, and every other check
  // still passed -- an empty 48dp button is exactly 48dp tall, an empty caption
  // overflows nothing, and a blank layout fits beautifully. A harness that
  // certifies an empty screen is worse than no harness.
  const bad = [...root.querySelectorAll("[data-s]")]
    .filter((e) => {
      const t = e.textContent.trim();
      return !t || t.includes("\u27e8") || t === "undefined";
    })
    .map((e) => e.getAttribute("data-s"));
  if (bad.length) fails.push(`missing or empty string key(s): ${[...new Set(bad)].join(", ")}`);

  // --- the address --------------------------------------------------------
  // Measured on the glyph run, and the room measured on the column that
  // constrains it. Comparing the run against its own box says "fits" for a
  // block-level element however badly it overflows, and says "zero slack" for a
  // content-sized one however much room is left.
  const code = needs("mac") ? root.querySelector(".code") : null;
  let mac = null;
  if (needs("mac") && !code) fails.push("no address");
  if (code) {
    const range = document.createRange();
    range.selectNodeContents(code);
    const text = r1(range.getBoundingClientRect().width);

    // How much wider the address could get before the composition breaks.
    //
    // This has now been wrong three times, always the same way: measured
    // against a box that is defined by the text it holds. As a stretching `1fr`
    // the cell reported hundreds of dp that belonged to the layout; as a
    // content-sized `auto` cell it reported zero; and with the identity column
    // itself content-sized it reported zero again one level up. A box that hugs
    // its text can never say how much room there is.
    //
    // The band is the only thing with a fixed width, so the answer is the slack
    // left in it once both zones and the gap between them are paid for. The
    // address may grow into all of it.
    const field = root.querySelector(".field");
    const identity = root.querySelector(".identity");
    const zone = root.querySelector(".codezone");
    const gap = parseFloat(getComputedStyle(field).columnGap) || 0;
    const pair = identity.getBoundingClientRect().width + gap + zone.getBoundingClientRect().width;
    const slack = field.clientWidth - pair;
    const room = r1(text + slack);

    const cs = getComputedStyle(code);
    mac = {
      text, room, spare: r1(room - text),
      fontPx: parseFloat(cs.fontSize),
      // The isolation that stops bidi reordering a Latin code in an RTL page.
      ltr: cs.direction === "ltr" && cs.unicodeBidi.includes("isolate"),
      shown: code.textContent.trim(),
    };
    if (mac.spare < 0 || code.scrollWidth > code.clientWidth + 1) {
      fails.push(`MAC has no room (${text}dp of text, ${room}dp available)`);
    }
    if (!mac.ltr) fails.push("MAC not isolated LTR — bidi may reorder it");
  }

  // --- line boxes: is a Latin line-height tall enough for this script? -----
  // The token line-heights were chosen against Latin. Thai stacks vowel marks
  // above and tone marks below; Devanagari hangs a headline and matras on both
  // sides; Arabic has deep descenders. All need more vertical room than Latin
  // at the same size -- and the element cannot report the problem, because its
  // box *is* the line-height.
  //
  // The yardstick is the glyphs' actual ink, from Canvas TextMetrics, not the
  // font's recommended line spacing. `line-height:normal` was tried first and
  // was useless: Noto Sans Arabic recommends 46dp for a 22sp string, so every
  // Arabic line "failed" while rendering perfectly. Recommended spacing is a
  // typographic suggestion; ink that exceeds the line box is a clip.
  const ctx = document.createElement("canvas").getContext("2d");
  function ink(e) {
    const cs = getComputedStyle(e);
    ctx.font = `${cs.fontStyle} ${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`;
    const m = ctx.measureText(e.textContent);
    return {
      ink: r1(m.actualBoundingBoxAscent + m.actualBoundingBoxDescent),
      up: r1(m.actualBoundingBoxAscent),
      down: r1(m.actualBoundingBoxDescent),
    };
  }
  const lines = [];
  for (const e of root.querySelectorAll(".foot, .cap, h1, .trial span, .lang span, .label, .name, .title, .count")) {
    if (!e.textContent.trim()) continue;
    const fixed = parseFloat(getComputedStyle(e).lineHeight);
    if (!isFinite(fixed)) continue;
    const m = ink(e);
    const cls = String(e.className || e.tagName).split(" ").pop() || e.tagName;
    lines.push({ on: cls, token: r1(fixed), ink: m.ink, headroom: r1(fixed - m.ink) });
    if (m.ink > fixed + 0.5) {
      fails.push(`ink taller than the line box on .${cls}: ${m.ink}dp of glyph in a ${fixed}dp line`);
    }
  }

  // --- the standing notice ------------------------------------------------
  const footer = root.querySelector(".foot");
  let foot = null;
  if (footer) {
    const b = footer.getBoundingClientRect();
    foot = { ...box(footer), lines: Math.round(b.height / 18) };
    if (b.bottom > bounds.bottom + 0.5 || b.height < 1) fails.push("footer off screen");
    if (!footer.textContent.trim()) fails.push("footer empty");
  } else if (needs("footer")) fails.push("no footer");

  // --- the header, which must stay one row --------------------------------
  const head = root.querySelector(".head, .phead");
  let header = null;
  if (head) {
    const kids = [...head.children].filter((e) => e.getBoundingClientRect().height > 0);
    const tallest = Math.max(...kids.map((e) => e.getBoundingClientRect().height));
    const hs = getComputedStyle(head);
    // The content box, not the padded one, and not a count of distinct tops:
    // children centred on one line have different tops by definition, so
    // counting tops called every language a wrap. A header taller than its
    // tallest child once padding is removed is the thing that means wrapped.
    const contentH = head.clientHeight -
      (parseFloat(hs.paddingTop) || 0) - (parseFloat(hs.paddingBottom) || 0);
    header = { ...box(head), items: kids.length, content: r1(contentH), tallest: r1(tallest) };
    if (contentH > tallest + 2) {
      fails.push(`header wrapped: ${r1(contentH)}dp of content, tallest item ${r1(tallest)}dp`);
    }
  } else if (needs("header")) fails.push("no header");

  // --- targets and QR ----------------------------------------------------
  const floor = isTv ? 56 : 48;
  // A control may be drawn smaller than the area that responds to it -- a
  // header chip at 48dp looks like a button. Where that is intended the element
  // declares `data-target`, and the check holds it to the declared number
  // instead of the drawn one. Undeclared, the drawn size is the target.
  const targets = [...root.querySelectorAll(".btn, .copy, .lang, .opt, .close")].map((e) => {
    const b = box(e);
    const declared = parseFloat(e.getAttribute("data-target") || "0");
    const effective = Math.max(b.h, declared);
    if (effective < floor - 0.5) {
      fails.push(`target too small: ${String(e.className).slice(0, 20)} ${effective}dp < ${floor}`);
    }
    return { name: (e.textContent.trim() || "copy").slice(0, 18), ...b, target: effective };
  });

  const qrEl = needs("qr") ? root.querySelector(".plate") : null;
  let qr = null;
  if (needs("qr") && !qrEl) fails.push("no QR plate");
  if (qrEl) {
    const b = box(qrEl);
    // The plate carries padding around the symbol, so pitch is measured on the
    // code itself: 21 modules plus a two-module quiet zone.
    const pad = parseFloat(getComputedStyle(qrEl).padding) || 0;
    qr = { ...b, code: r1(b.w - 2 * pad), pitch: r1((b.w - 2 * pad) / 25) };
    if (qr.pitch < 3.0) fails.push(`QR pitch ${qr.pitch}dp/module below 3.0 floor`);
  }

  const card = root.querySelector(".field, .panel");

  return {
    // Deliberately not called `frame`: the caller merges this into a row that
    // already has a `frame` naming the device, and an object spread would have
    // the box quietly overwrite the name. It did, and it left the whole
    // worst-case summary filtering on a value that could never match.
    viewport: { w: r1(bounds.width), h: r1(bounds.height) },
    doc,
    card: card ? box(card) : null,
    header, mac, qr, footer: foot, targets, lines, scrollers,
    dir: document.documentElement.dir,
    fails,
  };
}

async function main() {
  const { chromium } = loadPlaywright();
  const args = process.argv.slice(2);
  const opt = (name) => {
    const i = args.indexOf(`--${name}`);
    return i === -1 ? null : args[i + 1];
  };

  const file = opt("file") || "activation-mac.html";
  const conf = FILES[path.basename(file)];
  if (!conf) { console.error(`No frame list for ${file}.`); process.exit(2); }

  const frames = opt("frame") ? conf.frames.filter((f) => f[0] === opt("frame")) : conf.frames;
  const shots = opt("shots");

  // Resting state in every language, or every state in the languages that can
  // break one. Both are matrices; `""` is the resting state and is always in it.
  const wantStates = opt("state");
  const states = !wantStates ? [""]
    : wantStates === "all" ? ["", ...(conf.states || [])]
    : wantStates.split(",");
  const langs = opt("lang") ? opt("lang").split(",")
    : wantStates ? (conf.stateLangs || conf.langs)
    : conf.langs;

  warnFonts();

  const url = "file://" + path.resolve(file);
  const browser = await chromium.launch({ args: ["--no-sandbox", "--font-render-hinting=none"] });
  const rows = [];
  let failed = 0;

  for (const [frame, width, height, scale, note] of frames) {
    console.log(`\n${"=".repeat(78)}\n${frame}   ${width}x${height}dp   (${note})\n${"=".repeat(78)}`);

    for (const lang of langs) {
      for (const state of states) {
        const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
        await page.goto(`${url}?frame=${frame}&lang=${lang}&state=${state}`);
        await page.waitForTimeout(220);
        const seen = await page.evaluate(probe, {
          frame, isTv: frame === "tv" || frame.startsWith("tv-"), requires: conf.requires || [],
        });

        if (seen.missing) { console.log(`  ${lang}: MISSING frame`); failed++; }
        else {
          const tag = `${lang} (${WHY[lang] || "?"})${state ? " · " + state : ""}`.padEnd(30);
          const ok = seen.fails.length === 0;
          console.log(`  ${tag} ${ok ? "ok " : "FAIL"}  card ${seen.card ? seen.card.w + "x" + seen.card.h : "-"}` +
            `  head ${seen.header ? seen.header.w + "x" + seen.header.h : "-"}` +
            `  foot ${seen.footer ? seen.footer.w + "x" + seen.footer.h : "-"}` +
            `  MAC ${seen.mac ? seen.mac.text + "/" + seen.mac.room + " spare " + seen.mac.spare : "-"}` +
            `  QR ${seen.qr ? seen.qr.w + " @" + seen.qr.pitch + "dp" : "-"}` +
            `  dir ${seen.dir}`);
          for (const f of seen.fails) console.log(`      -> ${f}`);
          if (!ok) failed++;
          rows.push({ frame, lang, state, ...seen });
        }

        if (shots) {
          await page.screenshot({ path: path.join(shots, `${frame}-${lang}${state ? "-" + state : ""}.png`) });
        }
        await page.close();
      }
    }
  }

  await browser.close();

  // The worst case per frame, which is the number that decides the design.
  console.log(`\n${"=".repeat(78)}\nworst case per frame\n${"=".repeat(78)}`);
  for (const [frame] of frames) {
    const mine = rows.filter((r) => r.frame === frame);
    if (!mine.length) continue;
    // Only over the rows that have the thing. A file that has no address has no
    // worst address, and reporting one would mean the summary had invented it.
    const worstOf = (pick, cmp) => {
      const have = mine.filter((r) => pick(r) != null);
      return have.length ? have.reduce((a, b) => (cmp(pick(a), pick(b)) ? a : b)) : null;
    };
    const bigger = (a, b) => a >= b;
    const smaller = (a, b) => a <= b;
    const tallestCard = worstOf((r) => r.card && r.card.h, bigger);
    const tightestMac = worstOf((r) => r.mac && r.mac.spare, smaller);
    const tallestHead = worstOf((r) => r.header && r.header.h, bigger);
    const tallestFoot = worstOf((r) => r.footer && r.footer.h, bigger);

    console.log(`  ${frame}`);
    if (tallestCard) console.log(`    tallest surface ${tallestCard.card.h}dp  (${tallestCard.lang})`);
    if (tallestHead) console.log(`    tallest header  ${tallestHead.header.h}dp  (${tallestHead.lang})`);
    if (tallestFoot) console.log(`    tallest footer  ${tallestFoot.footer.h}dp  (${tallestFoot.lang})`);
    if (tightestMac) console.log(`    least MAC spare ${tightestMac.mac.spare}dp  (${tightestMac.lang})`);

    // What a scrolling list costs the reader, which is the number the argument
    // about whether it needs a search field has to be made against.
    const anyList = mine.find((r) => r.scrollers && r.scrollers.length);
    if (anyList) {
      for (const l of anyList.scrollers) {
        console.log(`    list            ${l.rows} rows in ${l.cols} columns at ${l.rowH}dp ` +
                    `-> ${l.visible} visible, ${l.screens} screens`);
      }
    }
    // The tightest line box on the frame: how much of the token's line-height
    // is left once the script's glyphs have taken theirs.
    const allLines = mine.flatMap((r) => r.lines.map((l) => ({ ...l, lang: r.lang })));
    if (allLines.length) {
      const worst = allLines.reduce((a, b) => (a.headroom <= b.headroom ? a : b));
      console.log(`    tightest line   .${worst.on} ${worst.ink}dp ink in ${worst.token}dp ` +
                  `-> ${worst.headroom}dp headroom  (${worst.lang})`);
      const byTok = {};
      for (const l of allLines) {
        const k = `.${l.on} @${l.token}`;
        if (!byTok[k] || byTok[k].headroom > l.headroom) byTok[k] = l;
      }
      for (const k of Object.keys(byTok).sort()) {
        const l = byTok[k];
        console.log(`      ${k.padEnd(22)} worst ink ${String(l.ink).padStart(5)}dp (${l.lang})  headroom ${l.headroom}dp`);
      }
    }
  }

  // `rows` holds every combination that was measured, passing or failing.
  console.log(failed === 0
    ? `\nAll ${rows.length} frame/language combinations fit. scroll = NONE, overflow = 0.\n`
    : `\n${failed} of ${rows.length} combinations failed.\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main();
