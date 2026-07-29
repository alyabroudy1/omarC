#!/usr/bin/env node
/*
 * Runtime check for the JavaScript we inject into sniffer WebViews.
 *
 * WHY THIS EXISTS
 * ---------------
 * The injected scripts live inside Kotlin raw strings, so the Kotlin compiler never looks at them
 * and `node --check` only proves they *parse*. A bad edit therefore ships silently and fails at
 * runtime inside the WebView, where the only symptom is `evaluateJavascript` handing back `null`.
 *
 * That is exactly what happened on 2026-07-29: renaming a variable left one stale read behind, the
 * extraction script threw `ReferenceError: textContent is not defined` on every poll, and DOM
 * extraction was dead for every provider — no source scraping, no deleted-video detection, no
 * anti-automation reporting. It parsed fine the whole time.
 *
 * So this harness EXECUTES each script against a minimal DOM stub. It catches the class of mistake
 * `node --check` cannot: undefined variables, bad property chains, typos in a rename.
 *
 * USAGE
 * -----
 *   node shared/tools/check-injected-js.js
 *
 * Exits non-zero on the first failure, so it can be wired into CI or a pre-commit hook.
 * Run it after ANY edit to the injected JS in VideoSnifferEngine.kt or AdBlocker.kt.
 */

const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '../..');
const ENGINE = path.join(REPO, 'shared/src/main/kotlin/com/cloudstream/shared/webview/VideoSnifferEngine.kt');
const ADBLOCK = path.join(REPO, 'shared/src/main/kotlin/com/cloudstream/shared/webview/AdBlocker.kt');

let failures = 0;
const pass = (m) => console.log(`  ✓ ${m}`);
const fail = (m) => { console.log(`  ✗ ${m}`); failures++; };

/** Pull the raw string containing `needle` out of a Kotlin source file. */
function extractRawString(file, needle) {
  const src = fs.readFileSync(file, 'utf8');
  const blocks = src.match(/"""[\s\S]*?"""/g) || [];
  const hit = blocks.map((b) => b.slice(3, -3)).filter((b) => b.includes(needle));
  if (hit.length !== 1) {
    throw new Error(`expected exactly 1 raw string containing ${JSON.stringify(needle)} in ` +
      `${path.basename(file)}, found ${hit.length}`);
  }
  return hit[0];
}

/** A DOM stub broad enough to execute every branch of the injected scripts. */
function makeDom(opts = {}) {
  const element = (over = {}) => Object.assign({
    id: '', className: '', dataset: {}, tagName: 'DIV',
    textContent: '', innerText: '', innerHTML: '',
    style: { setProperty() {} },
    shadowRoot: null, children: [], contentDocument: null,
    src: '', currentSrc: '', readyState: 0, paused: true, duration: NaN,
    querySelector: () => null, querySelectorAll: () => [], closest: () => null,
    getAttribute: () => null, remove() {}, appendChild() {},
    getBoundingClientRect: () => ({ width: 640, height: 360, top: 0, left: 0 }),
  }, over);

  const body = element({
    innerText: opts.bodyInnerText !== undefined ? opts.bodyInnerText : '',
    textContent: opts.bodyTextContent !== undefined ? opts.bodyTextContent : '',
    innerHTML: 'x'.repeat(opts.bodyLen || 1000),
  });

  const win = {
    innerWidth: 1920, innerHeight: 1080,
    location: { href: opts.href || 'https://host.test/e/abc', hostname: 'host.test' },
    getComputedStyle: () => ({
      position: opts.position || 'static', zIndex: String(opts.zIndex || 0),
      display: 'block', visibility: 'visible',
    }),
    MediaSource: function () {}, performance: { now: () => 1 },
    open() {}, alert() {}, confirm() { return false; }, prompt() { return null; },
    addEventListener() {}, removeEventListener() {},
  };

  const doc = {
    title: opts.title || '', readyState: 'complete', visibilityState: 'visible',
    referrer: opts.referrer || '', body, scripts: { length: 3 }, currentScript: null,
    querySelectorAll: (sel) => (opts.queryAll ? opts.queryAll(sel) : (sel === '*' ? [element()] : [])),
    querySelector: () => null, getElementById: () => null,
    createElement: () => element(), head: { appendChild() {} },
    addEventListener() {}, write() {}, open() {}, close() {},
  };

  return { win, doc, element };
}

/**
 * Install stubs as globals, run `body`, restore. Returns the script's value.
 *
 * `after` runs while the stubs are STILL installed — needed for anything the script deferred to a
 * timer, since those callbacks read `document` when they fire, not when they were registered.
 */
function runWithDom(body, dom, extraGlobals = {}, after) {
  const saved = {};
  const g = Object.assign({
    window: dom.win, document: dom.doc, location: dom.win.location,
    getComputedStyle: dom.win.getComputedStyle, MediaSource: dom.win.MediaSource,
    navigator: { userAgent: 'stub' }, URL,
  }, extraGlobals);
  for (const k of Object.keys(g)) { saved[k] = global[k]; global[k] = g[k]; }
  try {
    const out = new Function(body)();
    if (after) after();
    return out;
  } finally {
    for (const k of Object.keys(g)) { global[k] = saved[k]; }
  }
}

// ── 1. DOM extraction script ────────────────────────────────────────────────────────────────────
console.log('VideoSnifferEngine — DOM extraction script');
try {
  const body = extractRawString(ENGINE, 'Extraction complete');
  const logged = [];
  const dom = makeDom({
    title: 'Video not found',
    // The real-world shape that mattered: our own injected CSS empties innerText, so the failure
    // message is reachable only through textContent.
    bodyInnerText: '',
    bodyTextContent: 'File was deleted — this video is no longer available',
    bodyLen: 78415,
  });
  const out = runWithDom(body, dom, { console: { log: (...a) => logged.push(a.join(' ')) } });
  pass('executes without throwing');

  const line = logged.find((l) => l.includes('Extraction complete')) || '';
  if (!line) fail('never reached the final "Extraction complete" statement');
  else pass('runs to completion');

  if (/Invalid:\s*true/.test(line)) pass('detects a deleted-video page from textContent alone');
  else fail(`did not flag the deleted-video page — got: ${line}`);

  const m = line.match(/ScanLen:\s*(\d+)/);
  if (m && Number(m[1]) > 20) pass(`phrase scan saw ${m[1]} chars of text`);
  else fail(`phrase scan saw no text (ScanLen missing or ~0) — got: ${line}`);

  if (typeof out === 'string') {
    const j = JSON.parse(out);
    for (const k of ['videoCount', 'sources', 'invalidPageDetected', 'invalidReason', 'scanLen', 'blockReason']) {
      if (!(k in j)) fail(`result JSON missing key "${k}"`);
    }
    pass('result parses as JSON with the expected keys');
  }
} catch (e) {
  fail(`${e.constructor.name}: ${e.message}`);
}

// ── 2. Ad-blocker overlay script ────────────────────────────────────────────────────────────────
console.log('AdBlocker — overlay neutralisation script');
try {
  const body = extractRawString(ADBLOCK, 'Hid overlay');
  const touched = [];
  const dom = makeDom({ position: 'fixed', zIndex: 999999 });
  const el = (id, cls, txt, over = {}) => Object.assign({
    id, className: cls, dataset: {}, textContent: txt,
    style: { setProperty: (k) => touched.push(`${id}:${k}`) },
    querySelector: (s) => (over.q && over.q[s]) || null,
    closest: (s) => (over.c && over.c[s]) || null,
  }, over);

  const overlays = [
    el('adsbygoogle', '', ''),                                    // must be hidden
    el('errmsg', 'overlay-msg', 'File was deleted'),              // must survive: we read this
    el('skin', 'custom-skin', '', { c: { '[class*="player"]': {} } }), // must survive: player UI
  ];
  dom.doc.querySelectorAll = (sel) => (sel === 'div, aside, section' ? overlays : []);

  const timers = [];
  runWithDom(body, dom, {
    console: { log() {} },
    setInterval: (fn) => { timers.push(fn); return 1; },
    setTimeout: (fn) => { timers.push(fn); return 1; },
  }, () => timers.forEach((fn) => fn())); // fire the sweep while the stubs are live
  pass('executes without throwing');

  if (timers.length) pass(`registered ${timers.length} interval(s)`);
  else fail('registered no interval — overlay sweep would never run');

  if (touched.some((t) => t.startsWith('adsbygoogle:'))) pass('hides an adsbygoogle overlay');
  else fail('failed to hide an adsbygoogle overlay');

  if (!touched.some((t) => t.startsWith('errmsg:'))) pass('leaves the "file deleted" message alone');
  else fail('touched the failure message — the deleted-video detector needs to read it');

  if (!touched.some((t) => t.startsWith('skin:'))) pass('leaves custom player UI alone');
  else fail('touched custom player UI — this blanks the page for the user');
} catch (e) {
  fail(`${e.constructor.name}: ${e.message}`);
}

// ── 3. Scripts that only need to parse ──────────────────────────────────────────────────────────
console.log('Parse-only checks');
for (const [file, needle, label] of [
  [ENGINE, 'body > *:not(iframe)', 'BLANK_NON_PLAYER_JS'],
  [ENGINE, 'width: 100vw', 'FULLSCREEN_IFRAME_JS'],
]) {
  try {
    new Function(extractRawString(file, needle));
    pass(`${label} parses`);
  } catch (e) {
    fail(`${label}: ${e.message}`);
  }
}

console.log(failures === 0 ? '\nAll injected-JS checks passed.' : `\n${failures} check(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
