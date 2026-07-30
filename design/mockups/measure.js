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
 *   node measure.js --shots ./out         # PNGs while measuring
 *   node measure.js --file shell-home.html
 *
 * Exit code is non-zero when any frame in any language fails, so it gates.
 *
 * Needs Playwright, which is not a dependency of this repository -- nothing in
 * CI renders a mockup, and this is a tool for whoever is drawing one.
 * `npm i -g playwright` is enough; the browser is already present.
 *
 * It also needs the Noto faces Android ships, or Thai and Devanagari fall back
 * to whatever the machine has and their line boxes -- the thing being measured
 * -- stop being the device's. Noto Sans, Noto Sans Arabic, Noto Sans Thai and
 * Noto Sans Devanagari; the script says so if they are missing.
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
  },
};

/** Why each language is in the list, printed so a failure explains itself. */
const WHY = {
  en: "baseline", ar: "RTL + bidi", de: "expansion", fi: "long words",
  th: "line height", hi: "Devanagari line height", bn: "Bengali conjuncts",
  ja: "CJK line breaking",
  zh: "CJK line breaking",
};

const MIN = { touch: 48, tv: 56, qrPitchDp: 3.0, qrModules: 21 };

function warnFonts() {
  const need = ["Noto Sans", "Noto Sans Arabic", "Noto Sans Thai", "Noto Sans Devanagari"];
  let have = "";
  try { have = execSync("fc-list : family", { encoding: "utf8" }); } catch (_) { return; }
  const missing = need.filter((f) => !have.includes(f));
  if (missing.length) {
    console.log(`\n  ! missing font(s): ${missing.join(", ")}`);
    console.log("    Thai and Devanagari line heights will not be the device's.\n");
  }
}

/** Runs inside the page. Everything measured, nothing assumed. */
function probe({ frame, isTv }) {
  const root = document.getElementById(frame);
  if (!root) return { missing: true };

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
  const outside = [...root.querySelectorAll("*")].filter((e) => {
    const b = e.getBoundingClientRect();
    if (b.height <= 0 || b.width <= 0) return false;
    return b.bottom > bounds.bottom + 0.5 || b.top < bounds.top - 0.5 ||
           b.right > bounds.right + 0.5 || b.left < bounds.left - 0.5;
  }).map((e) => String(e.className || e.tagName).slice(0, 40));
  if (outside.length) fails.push(`paints outside: ${[...new Set(outside)].slice(0, 5).join(", ")}`);

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
  const code = root.querySelector(".code");
  let mac = null;
  if (code) {
    const range = document.createRange();
    range.selectNodeContents(code);
    const text = r1(range.getBoundingClientRect().width);

    // "Room" is how much wider the address could get before its row overflows,
    // which is the container's content width less the label, the copy control
    // and the gaps between them. Measuring against the value's own cell was
    // wrong twice: as a stretching `1fr` it reported hundreds of dp of slack
    // that belonged to the layout, and as a content-sized `auto` it reported
    // zero because the cell hugs the glyphs. Neither is a fit.
    const row = code.closest(".values");
    const label = row.querySelector(".label");
    const copyBtn = row.querySelector(".icon-btn");
    const holder = row.parentElement;
    const hs = getComputedStyle(holder);
    const avail = holder.clientWidth -
      (parseFloat(hs.paddingLeft) || 0) - (parseFloat(hs.paddingRight) || 0);
    const gap = parseFloat(getComputedStyle(row).columnGap) || 0;
    const room = r1(avail - label.getBoundingClientRect().width -
      copyBtn.getBoundingClientRect().width - 2 * gap);

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
  for (const e of root.querySelectorAll(".footer, .cap, h1, .pill-trial, .pill-lang span")) {
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
  const footer = root.querySelector(".footer");
  let foot = null;
  if (footer) {
    const b = footer.getBoundingClientRect();
    foot = { ...box(footer), lines: Math.round(b.height / 18) };
    if (b.bottom > bounds.bottom + 0.5 || b.height < 1) fails.push("footer off screen");
    if (!footer.textContent.trim()) fails.push("footer empty");
  } else fails.push("no footer");

  // --- the header, which must stay one row --------------------------------
  const head = root.querySelector(".head");
  let header = null;
  if (head) {
    const kids = [...head.children].filter((e) => e.getBoundingClientRect().height > 0);
    const tops = new Set(kids.map((e) => Math.round(e.getBoundingClientRect().top)));
    header = { ...box(head), items: kids.length, rows: tops.size };
    const tallest = Math.max(...kids.map((e) => e.getBoundingClientRect().height));
    if (header.h > tallest + 2) fails.push(`header wrapped to ${r1(header.h)}dp (tallest item ${r1(tallest)}dp)`);
  } else fails.push("no header");

  // --- targets and QR ----------------------------------------------------
  const floor = isTv ? 56 : 48;
  const targets = [...root.querySelectorAll(".btn, .icon-btn")].map((e) => {
    const b = box(e);
    if (b.h < floor - 0.5) fails.push(`target too small: ${e.className.slice(0, 24)} ${b.h}dp < ${floor}`);
    return { name: (e.textContent.trim() || "copy").slice(0, 18), ...b };
  });

  const qrEl = root.querySelector(".qr");
  let qr = null;
  if (qrEl) {
    const b = box(qrEl);
    qr = { ...b, pitch: r1(b.w / 25) };   // 21 modules + a 2-module quiet zone
    if (qr.pitch < 3.0) fails.push(`QR pitch ${qr.pitch}dp/module below 3.0 floor`);
  }

  const card = root.querySelector(".card");

  return {
    // Deliberately not called `frame`: the caller merges this into a row that
    // already has a `frame` naming the device, and an object spread would have
    // the box quietly overwrite the name. It did, and it left the whole
    // worst-case summary filtering on a value that could never match.
    viewport: { w: r1(bounds.width), h: r1(bounds.height) },
    doc,
    card: card ? box(card) : null,
    header, mac, qr, footer: foot, targets, lines,
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
  const langs = opt("lang") ? opt("lang").split(",") : conf.langs;
  const shots = opt("shots");

  warnFonts();

  const url = "file://" + path.resolve(file);
  const browser = await chromium.launch({ args: ["--no-sandbox", "--font-render-hinting=none"] });
  const rows = [];
  let failed = 0;

  for (const [frame, width, height, scale, note] of frames) {
    console.log(`\n${"=".repeat(78)}\n${frame}   ${width}x${height}dp   (${note})\n${"=".repeat(78)}`);

    for (const lang of langs) {
      const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
      await page.goto(`${url}?frame=${frame}&lang=${lang}`);
      await page.waitForTimeout(220);
      const seen = await page.evaluate(probe, { frame, isTv: frame === "tv" });

      if (seen.missing) { console.log(`  ${lang}: MISSING frame`); failed++; }
      else {
        const tag = `${lang} (${WHY[lang] || "?"})`.padEnd(30);
        const ok = seen.fails.length === 0;
        console.log(`  ${tag} ${ok ? "ok " : "FAIL"}  card ${seen.card ? seen.card.w + "x" + seen.card.h : "-"}` +
          `  head ${seen.header ? seen.header.w + "x" + seen.header.h : "-"}` +
          `  foot ${seen.footer ? seen.footer.w + "x" + seen.footer.h : "-"}` +
          `  MAC ${seen.mac ? seen.mac.text + "/" + seen.mac.room + " spare " + seen.mac.spare : "-"}` +
          `  QR ${seen.qr ? seen.qr.w + " @" + seen.qr.pitch + "dp" : "-"}` +
          `  dir ${seen.dir}`);
        for (const f of seen.fails) console.log(`      -> ${f}`);
        if (!ok) failed++;
        rows.push({ frame, lang, ...seen });
      }

      if (shots) await page.screenshot({ path: path.join(shots, `${frame}-${lang}.png`) });
      await page.close();
    }
  }

  await browser.close();

  // The worst case per frame, which is the number that decides the design.
  console.log(`\n${"=".repeat(78)}\nworst case per frame\n${"=".repeat(78)}`);
  for (const [frame] of frames) {
    const mine = rows.filter((r) => r.frame === frame);
    if (!mine.length) continue;
    const tallestCard = mine.reduce((a, b) => (a.card.h >= b.card.h ? a : b));
    const tightestMac = mine.reduce((a, b) => (a.mac.spare <= b.mac.spare ? a : b));
    const tallestHead = mine.reduce((a, b) => (a.header.h >= b.header.h ? a : b));
    const tallestFoot = mine.reduce((a, b) => (a.footer.h >= b.footer.h ? a : b));
    console.log(`  ${frame}`);
    console.log(`    tallest card    ${tallestCard.card.h}dp  (${tallestCard.lang})`);
    console.log(`    tallest header  ${tallestHead.header.h}dp  (${tallestHead.lang})`);
    console.log(`    tallest footer  ${tallestFoot.footer.h}dp  (${tallestFoot.lang})`);
    console.log(`    least MAC spare ${tightestMac.mac.spare}dp  (${tightestMac.lang})`);
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

  console.log(failed === 0
    ? `\nAll ${rows.length} frame/language combinations fit. scroll = NONE, overflow = 0.\n`
    : `\n${failed} of ${rows.length + failed} combinations failed.\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main();
