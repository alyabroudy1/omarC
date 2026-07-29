# CimaNow Watch Page Decryption Handover & Cheatsheet

> **⚠️ CURRENT IMPLEMENTATION (2026-07) — read this and §0 first.**
> **Sections 2–11 are HISTORICAL** and describe a three-layer strategy architecture that was
> **deleted** in `898efc74`. They are kept only for the format timeline (§9) and as a record of what
> was tried. Do not implement from them; §0 lists what they get wrong.
>
> We do **not** decrypt in Kotlin and we do **not** eval extracted scripts. The **WebView runs the
> page's own decryptor**; we only feed it the bytes and read the result. `loadLinks` →
> `resolveViaWebViewSandbox`:
> 1. HTTP-navigate the freex chain to the timer (blog-post) page (`navigateToTimerPageViaHttp`).
> 2. `NavigationEngine.execute(...)` follows countdown → `get-link.php` → the `/watching/` URL and
>    captures its raw (still-encrypted) HTTP response (`mainFrameHtml`).
> 3. `decryptViaSandbox` → `NavigationEngine.renderHtmlInSandbox` renders that HTML as a **real
>    navigation** to the `/watching/` URL (NOT `loadDataWithBaseURL`) with the blog-post page as
>    `Referer`, lets the inline decryptor run untouched, and reads the decrypted `<li data-index>`
>    server list back via an **in-page reader that streams it out as chunked `console.log`**
>    (`<tag>B` / `<tag>C<i>` / `<tag>E` → `onConsoleMessage`, `<tag>` random per run). Not
>    `addJavascriptInterface` (the gate sniffs `window.CS_BRIDGE`) and not `prompt()` (the gate
>    replaces `window.prompt` to swallow our messages).
>
> **The gate greps inline script source for our markers** (2026-07-25: it matches the literals
> `__CSX__` and `[RD]`, taken from earlier versions of this reader) and decrypts nothing on a hit.
> So the reader must contain **no fixed strings** — all tokens derive from a per-run random tag —
> and it **deletes its own `<script>` node** first thing, before the gate enumerates scripts.
> 4. Parse with **Jsoup**; resolve each server via `core.php` + extractors; parse `#download`.
>
> **Why it must be shaped exactly this way** — the watch page's anti-bot (decoded 2026-07):
> - Decryptor aborts if `Function.prototype.toString.call(document.write)` lacks `[native code]` →
>   **never hook `document.write`**.
> - Decryptor aborts if `location.hostname` is empty → must be a real navigation to `/watching/`.
> - An LZString-obfuscated gate runs `if(document.referrer.indexOf('rm.freex2line.online')===-1)
>   location.replace('/home')`, which aborts the `document.write` parse before the server list →
>   **must load with the freex blog-post page as Referer**.
> - After decrypting it installs `isBot()` = stack contains `evaluatejavascript`, or `<anonymous>`
>   with no `http` → sabotages `querySelectorAll`/`innerHTML`/etc. So DOM reads via
>   `evaluateJavascript` are defeated; only an **inline page-context script (http stack)** reads real
>   data. (Full mechanism also recorded in the agent memory `cimanow-antibot-mechanism`.)
>
> **2026-07-29 — two changes, both load-bearing:**
> - The reader is injected at **offset 0**, not after `<head>`. The payload script precedes `<head>`
>   and replaces the document, which used to discard the reader before it ever ran. See §0.1 rule 12.
> - The reader now reports on **two channels** — chunked `console.log` (status + bulk exfil) and
>   `document.title` → `onReceivedTitle` (status only). The offset-0 script tampers with console
>   methods, so a single channel cannot be trusted and a silent reader cannot be diagnosed.
>   `renderHtmlInSandbox` logs a `Reader channel report` verdict line; read it first. See §0.2.

---

## 0. IMPLEMENTATION RULES — read before changing anything

Every rule below is a **thing that was already built, shipped, and broke**. The site's gate is an
arms race with roughly 40 commits of history behind it; the cost of re-learning any of these is a
day. If you are about to do something on this list, the answer is already known.

### 0.1 Never do these

| # | Do not | Why — and when we learned it |
|---|---|---|
| 1 | Decrypt in Kotlin / replicate the cipher | Built, then deleted in `898efc74` (−806 lines, 2026-07-24). The cipher rotates **every few hours** (see the format timeline in §9 — five formats in six months), and worse, the plaintext is gated behind browser-context checks that have nothing to do with the maths. Replicating the cipher does not get you the plaintext. |
| 2 | `eval` extracted page scripts | Same reason. The gate checks its environment, not just its input. |
| 3 | Hook / wrap / polyfill `document.write` | Gate aborts if `Function.prototype.toString.call(document.write)` lacks `[native code]`. (Note §11's last answer contradicts this — §11 is historical and wrong.) |
| 4 | `loadDataWithBaseURL` | Gate aborts if `location.hostname` is empty. The document must be a **real navigation** to the `/watching/` URL, served from our own bytes via `shouldInterceptRequest`. |
| 5 | Navigate without a Referer | Gate runs `if (document.referrer.indexOf('rm.freex2line.online') === -1) location.replace('/home')`, aborting the parse before the server list. Pass the blog-post page as `Referer`. |
| 6 | `addJavascriptInterface` | Gate sniffs `window.CS_BRIDGE` / `window.__decryptedHtml` / `window.__captured`. Killed this channel. |
| 7 | Set anything on `window` | Same sniff. The reader must be a bare IIFE. |
| 8 | `prompt()` as an exfil channel | Gate replaces `window.prompt` to swallow our messages. Killed this channel. |
| 9 | `webView.evaluateJavascript` to read the decrypted DOM | Post-decrypt `isBot()` checks the call stack for `evaluatejavascript`, or `<anonymous>` with no `http`, and sabotages `querySelectorAll` / `innerHTML`. Reads must come from an **inline page script** (stack = the document's http URL). |
| 10 | Put any fixed string in the reader | Gate greps every inline script's `innerHTML` for known markers — as of 2026-07-25 the literals `__CSX__` and `[RD]`, both ours from earlier versions. All tokens must derive from a per-run random tag, and the reader must delete its own `<script>` node first thing. |
| 11 | Patch or freeze `Element.prototype.remove` | Gate tests it by appending an `<li data-index>`, removing it and checking `parentNode`. |
| 12 | **Inject the reader after `<head>`** | 2026-07-29. The payload script sits at **offset 0**, *ahead* of `<head>` (~966), and replaces the document via `document.open()`/`write()` — which resets the parser and discards every byte not yet parsed, the reader included. It never executed at all. Inject at offset 0 (after a `<!DOCTYPE` if present, or the page drops into quirks mode). |
| 13 | **Trust the console channel alone** | 2026-07-29. That same offset-0 script begins `!function(){try{for(var o=["lo…` — iterating what is almost certainly console method names. `console.log.bind(console)` only captures the genuine function if the reader runs **first**. Keep the second (title) channel. |
| 14 | **Chase subresource / MIME failures** | 2026-07-29. The sandbox deliberately serves no subresources, so jQuery, `owl.carousel`, the rotating-name anti-bot script and `animate.css` all come back as `text/html` and Chrome refuses them, giving `$ is not defined`. **Decryption works anyway** (`li=6` captured). This is noise, not a cause — do not spend time on it. |
| 15 | Add an Upnshare extractor | 2026-07-29. It was the one server that reliably burned its full 20s budget and returned nothing. Removed. When no extractor claims an iframe the sniffer takes over, so there is nothing to gain. |

### 0.2 Always do these

- **Keep both status channels.** The reader reports on chunked `console.log` *and* on `document.title`
  → `onReceivedTitle`. Two channels exist because a silent reader is otherwise undiagnosable: a
  wrapped `console.log` and a reader that never ran look identical from Kotlin.
- **Read `Reader channel report` first.** It prints the diagnosis rather than leaving it to inference:
  | verdict | meaning | action |
  |---|---|---|
  | both channels alive | reader ran; gate aborted upstream | read `li=` / `decoy=` / `dwNative=` in the `[rd]` diagnostics |
  | TITLE ONLY | `console.log` is being swallowed again | port the chunked exfil protocol to the title channel |
  | SILENT ON BOTH | the reader never executed | injection point / document serving — **not** the cipher |
  | console only | title writes blocked (gate now guards `Document.title`) | new channel needed |
- **Treat "no output" as the gate's designed behaviour, not as evidence of a decryption failure.**
  The pre-decrypt gate sets `_isB` and `return`s, emitting *nothing*. Absence of output tells you
  nothing about the cipher. Do not infer "the decryptor ran" from unrelated console warnings —
  Chrome's `document.write` intervention warnings about CDN scripts come from the page template and
  fire regardless.
- **Check the reader is still valid JS after editing it.** It is a Kotlin raw string, so a typo is a
  silent runtime failure that presents as SILENT ON BOTH — a false positive on our own diagnostic:
  ```bash
  # extract readerScript, substitute the $-interpolations, then:
  node --check /tmp/reader.js
  ```

### 0.3 Server-resolution phase (after decryption)

Decryption yields ~6 servers; each is resolved through `core.php` to an iframe, then handed to an
extractor or the sniffer. Two constraints here, both learned the hard way:

- **`SERVER_RESOLVE_TIMEOUT_MS` (20 s) is a per-server cap and is not sufficient on its own.** The
  resolution loop is a `coroutineScope`, i.e. a barrier — it waits for the slowest child even when
  every other server has already produced links. Measured 2026-07-29: five servers resolved in
  240 ms, then one straggler burned its full 20 s, which was **59 % of a 33.7 s `loadLinks`**.
- Hence **`STRAGGLER_GRACE_MS` (2.5 s)**: once the first playable link exists, the remaining servers
  get a short grace window and are then cancelled (`⏭ N link(s) ready — cancelling M …`). Do not
  remove this thinking the per-server cap covers it; it does not. Do not drop the grace window to
  zero either — a nearly-finished server should still be allowed to contribute an alternative source.
- `foundLinks` must stay a synchronized list: it is appended from all server coroutines concurrently
  and read by the watchdog.

### 0.4 Current known-good shape (verified 2026-07-29)

Sandbox decrypt **657 ms**, `captured li=6 fragLen=4805`, `hasLiDataIndex=true`, playback reached
first frame. `loadLinks` end-to-end ≈16 s, of which ~8.4 s is the site's own countdown guard — that
countdown is server-enforced and is the floor for this provider.

---

## 1. High-Level Flow Summary

To play videos on CimaNow, the client must traverse a multi-step redirection and token-generation pipeline:
1. **Movie/Episode Page:** Scraped for the `freex2line` URL (e.g., `https://rm.freex2line.online/loadon/?link=...`).
2. **Loadon Page:** Contains redirects to a `redirectingfree` page.
3. **Redirectingfree Page:** Sets cookies and redirects to a blog post template page (e.g., `https://rm.freex2line.online/2020/02/blog-post.html`).
4. **Blog Post Page:** Contains a configuration block (`window._0x_cfg`) with a dynamic token.
5. **countdown bypass:** The client must wait **11 seconds** before calling `get-link.php?token=...` to bypass the server-side countdown guard.
6. **Watch URL Retrieval:** `get-link.php` returns a JSON object containing the watch URL: `https://cimanow.cc/.../watching/?token=...`.
7. **Obfuscated Watch Page:** Fetching the watch page returns HTML containing an inline Javascript block that dynamically decrypts and writes the actual player DOM elements.

---

## 2. Architecture Overview — Three-Layer Defense

CimaNow changes encryption **every few hours** deliberately. Strategy-based detection (matching specific variable names/patterns) is fundamentally fragile — it tries to **replicate** decryption logic, which breaks every time the logic changes.

The truly format-agnostic approach: **execute the original decryption JavaScript in a sandbox** instead of replicating it. The JS sandbox is immune to all parameter/variable/operation changes because it runs the exact same code the site provides.

```
┌──────────────────────────────────────────────────────────────────┐
│                     decryptWatchHtml(html)                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─ Phase 1: KNOWN STRATEGIES (fast path, <50ms) ────────────┐  │
│  │  Tries to replicate known encryption formats by pattern    │  │
│  │  matching. BREAKS when format changes. Optimization only.  │  │
│  │                                                            │  │
│  │  AtobConfig  →  Version D  →  Version C  →  B  →  A       │  │
│  │                                                            │  │
│  │  Strategy pattern: canHandle(html)? → decrypt(html)        │  │
│  │  If any succeeds → Validator checks → return               │  │
│  └────────────────────────────────────────────────────────────┘  │
│                           ↓ (if null)                            │
│  ┌─ Phase 2: JS SANDBOX EXECUTION (format-agnostic, 1-5s) ───┐  │
│  │  Extracts the decryption script from the watch page and    │  │
│  │  executes it in a sandboxed WebView with polyfills.        │  │
│  │  IMMUNE to format changes — runs the original JS.          │  │
│  │                                                            │  │
│  │  polyfill: document.write → capture output                 │  │
│  │  polyfill: atob → Base64 decode                            │  │
│  │  polyfill: String.fromCharCode → identity                  │  │
│  │  Execute all scripts from watch page in order              │  │
│  │  Capture the decrypted HTML from document.write            │  │
│  └────────────────────────────────────────────────────────────┘  │
│                           ↓ (if null)                            │
│  ┌─ Phase 3: WEBVIEW NAVIGATION (existing fallback, 5-120s) ─┐  │
│  │  Full browser navigation flow: movie → freex2line → blog  │  │
│  │  → HMAC → get-link.php → watch page → extract servers     │  │
│  │  Works through Cloudflare, all JS, all anti-scraping.     │  │
│  └────────────────────────────────────────────────────────────┘  │
│                           ↓ (if null)                            │
│  return null → caller triggers error handling                    │
│                                                                  │
│  ┌─ Validator (runs after EVERY phase) ─────────────────────┐   │
│  │  Checks output for:                                       │   │
│  │  • data-index="..." attribute                             │   │
│  │  • data-id="..." attribute                                │   │
│  │  • HTML structural elements (<html>, <li>, <div>)         │   │
│  │  If invalid → try next phase                              │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### Why Phase 2 is truly immune to "every few hours" changes

| What CimaNow changes | Strategy (Phase 1) | Sandbox (Phase 2) |
|---|---|---|
| Variable names (e.g. `_ve0955` → `_x9k2m1`) | ❌ Breaks | ✅ Unaffected — JS doesn't care about names |
| Key formula (e.g. new math operation) | ❌ Breaks | ✅ Unaffected — JS computes it correctly |
| Delimiter (`*` → `#`) | ❌ Breaks | ✅ Unaffected — JS splits correctly |
| parseInt base (35 → 28) | ❌ Breaks | ✅ Unaffected — JS parses correctly |
| Array vs string payload | ❌ Breaks | ✅ Unaffected — JS handles both |
| New anti-tampering wrapper | ❌ Breaks | ✅ Unaffected — we run all scripts in order |
| `atob` → custom decoder | ❌ Breaks | ✅ Unaffected — runs the actual decoder |
| Completely novel algorithm | ❌ Breaks | ✅ Unaffected — runs the actual algorithm |

---

## 3. Phase 1: Known Strategies (Optimization Layer)

Each strategy implements:
```kotlin
interface DecryptionStrategy {
    val name: String
    fun canHandle(html: String): Boolean
    fun decrypt(html: String): String?
}
```

### 3.1 AtobConfigStrategy (Verified active — July 4, 2026)

**Detection:** `atob\s*\(\s*'([A-Za-z0-9+/=]+)'\s*\)` where decoded is `^\d+,\d+,\d+,\d+,[0-9a-f]+$`

**Example from live page:**
```javascript
var _cfg = atob('MjUwMDAsNjgwMDAsMjc0MCwzNSwxNmY4ZTk=').split(',');
// _cfg = ["25000", "68000", "2740", "35", "16f8e9"]
var _k = parseInt(_cfg[0]) + (parseInt(_cfg[4], 16) % parseInt(_cfg[1]));
// _k = 25000 + (0x16f8e9 % 68000) = 25000 + 9513 = 34513
var _pLoad = _ve0955.join('');   // ARRAY joined
var _chunks = _pLoad.split('*'); // delimiter = *
// each chunk:
//   atob(chunk) → split '-' → parseInt([1], 35) → (n - 2740) XOR 34513 → char
```

**Payload:** Find the large variable near the `atob()` call. Could be:
- Array `[...]` → extract all string literals, join (current)
- String `'...'` → extract string content directly

**Config fields:**
| Index | Field | Purpose | Example |
|-------|-------|---------|---------|
| 0 | base | Base value for key | 25000 |
| 1 | modulo | Modulo for hex hash | 68000 |
| 2 | subtract | Value to subtract from parsed int | 2740 |
| 3 | baseN | Radix for parseInt | 35 |
| 4 | hex | Hex fragment for key derivation | 16f8e9 |

### 3.2 Version D (July 2, 2026 — Page hash formula)

```javascript
var _pHsh = "0efb06ef";
var _kV = 50000 + (parseInt(_pHsh.substring(0,6), 16) % 100000);
// payload var name: "_" + _pHsh.substring(2, 7)
// delimiter: |
// each chunk: base36 parseInt, subtract 1337, XOR key
```

### 3.3 Version C (June 2026 — Dynamic local sum)

```javascript
var _x1 = 45132; var _x2 = 45132; // key = _x1 + _x2 + ... 
// delimiter: * or @
// each chunk: digits only, XOR key
```

### 3.4 Version B (Mid 2026 — Array sum)

```javascript
var _oArr = [39597, 39598, 39597]; // key = sum of elements
```

### 3.5 Version A (Early 2026 — Key difference)

```javascript
var _dk1 = 123456; var _dk2 = 100000; // key = _dk1 - _dk2
```

---

## 4. Phase 2: JS Sandbox Execution (Robustness Layer)

The core idea: **don't understand the encryption, just run it.**

### How it Works

1. **Extract** all `<script>` blocks from the watch page HTML (preserving order)
2. **Inject polyfills** before the scripts:
   - `document.write = function(html) { captured += html; }`
   - `document.open = function() {}` (no-op)
   - `document.close = function() {}` (no-op)
   - `atob = function(str) { return androidBase64Decode(str); }` — using Android's Base64
   - `decodeURIComponent(escape(str))` — identity function for ASCII output
   - `String.fromCharCode(n)` — pass through
3. **Execute** all scripts in a sandboxed WebView (about:blank, network disabled)
4. **Capture** the `document.write` output — this is the decrypted HTML
5. **Validate** with the shared Validator

### Why this works even with anti-tampering

The current anti-tampering script (Script 0) blocks `setAttribute('src', ...)` and `setAttribute('href', ...)` when devtools are detected. In our sandbox:
- No devtools → checks pass
- We don't set `src`/`href` attributes → even if checks triggered, no impact
- We only capture `document.write` output

### Implementation via NavigationEngine

The `NavigationEngine` (shared module) already has all the primitives:
```kotlin
// NavigationEngine.kt:835 — already exists
private suspend fun executeJsInWebView(webView: WebView, javascript: String): String?
```

We need to:
1. Expose a public method in `WebViewFlowHelper` or create a new `JsSandbox` utility
2. Provide the decryption scripts + polyfills as the JS to execute
3. Capture the result

```kotlin
// Proposed interface:
suspend fun decryptViaSandbox(watchHtml: String): String? {
    val scripts = extractAllScripts(watchHtml)
    val sandboxJs = buildPolyfill() + scripts.joinToString("\n") + "\nreturn window._captured;"
    return executeJsInWebView(sandboxJs)
}
```

### Timeout Budget
- JS execution: ~1-3 seconds for 3MB payload
- Total Phase 2 budget: 5 seconds max
- If timeout → fall through to Phase 3

---

## 5. Phase 3: WebView Navigation (Ultimate Fallback)

Already exists via `WebViewFlowHelper.navigateMovieToWatchPage()`. Full browser navigation:
- Loads movie page
- Waits for freex2line redirect
- Passes through Cloudflare challenges
- Follows redirect chain (loadon → redirectingfree → blog-post)
- Waits for countdown (11s + JS execution)
- Captures watching URL via interceptor
- Renders watch page and extracts servers via JS

**Time:** 20-120 seconds
**Resilience:** Works through ALL anti-scraping measures
**Cost:** Slow, user-facing browser window

---

## 6. Validator (Shared)

```kotlin
fun isValidDecryptedHtml(output: String): Boolean {
    if (output.isBlank()) return false
    // Must contain server list items
    val hasServerData = output.contains("data-index=") && output.contains("data-id=")
    // Must look like HTML
    val hasHtmlStructure = output.contains("<html", ignoreCase = true) ||
                           output.contains("<!DOCTYPE", ignoreCase = true) ||
                           output.contains("<li")
    return hasServerData && hasHtmlStructure
}
```

If Validator fails → log the output snippet and try next phase.

---

## 7. Adding a New Strategy (when format changes)

New strategies are OPTIMIZATIONS, not requirements. The sandbox (Phase 2) will still work.

To add a strategy for better performance:

1. **Capture a live watch page:**
   ```bash
   python3 CimaNowProviderV2/test/fetch_and_decrypt.py
   ```
   Saves raw HTML to `/tmp/cimanow_watch_raw.html`.

2. **Analyze** the script blocks for the new pattern — look at the JS that runs in the sandbox.

3. **Create the strategy:**
   ```kotlin
   class MyNewStrategy : DecryptionStrategy {
       override val name = "MyNewStrategy"
       override fun canHandle(html: String): Boolean = /* signature regex */
       override fun decrypt(html: String): String? = /* decryption logic */
   }
   ```

4. **Register** in the strategy list at appropriate priority.

5. **Update this document** with the new format details.

---

## 8. Troubleshooting

| Symptom | Root Cause | Action |
|---------|-----------|--------|
| Phase 1 all fail, Phase 2 succeeds | New encryption format | Optional: add new strategy for speed |
| Phase 1 and 2 both fail | Script structure changed (e.g., anti-tampering blocks execution) | Check polyfills; inspect raw scripts |
| Phase 2 timeouts | 3MB payload processing slow in WebView | Increase timeout; check for infinite loops |
| All phases fail | Network/redirect chain issue | Check blog-post.html HMAC flow; verify cookies |
| Validator rejects Phase 2 output | Decryption partially worked but format changed | Log the output for analysis |
| `N/A` | `N/A` | `N/A` |

### Diagnostic: When sandbox fails

Check what the JS sandbox captured:
```kotlin
// Log the captured output regardless of validation
Log.d(TAG, "Sandbox output (first 2000): ${sandboxOutput?.take(2000)}")
```

If output looks like HTML but missing `data-index`, the decryption worked but the page structure changed. If output is garbage, the JS execution failed.

---

## 9. Historical Format Timeline

| Date | Strategy | Key Source | Delimiter | parseInt Base | Math |
|------|----------|-----------|-----------|---------------|------|
| Early 2026 | A | `_dk1 - _dk2` | `@` | 10 | XOR key |
| Mid 2026 | B | `_oArr.sum()` | `@` / `*` | 10 | XOR key |
| June 2026 | C | `_x1 + _x2 + ...` | `*` | 10 | XOR key |
| July 2 | D | `_pHsh` hex hash | `\|` | 36 | `(n-1337) XOR key` |
| July 4 | AtobConfig | atob CSV config | `*` | 35 | `(n-2740) XOR key` |

---

## 10. File Reference

| File | Purpose |
|------|---------|
| `CimaNowProvider.kt` — `decryptWatchHtml()` | Three-phase dispatcher |
| `CimaNowProvider.kt` — strategy classes | Known format replicators (optimization) |
| `CimaNowProvider.kt` — `decryptViaSandbox()` | JS sandbox execution (robustness) |
| `shared/.../NavigationEngine.kt` — `executeJsInWebView()` | WebView JS evaluation primitive |
| `test/fetch_and_decrypt.py` | Full redirect chain + decryption test |
| `test/DECRYPTION_REPORT.md` | Detailed analysis notes |

---

## 11. Design Decision Record

### Why not just use WebView navigation for everything?
Speed. Phase 3 takes 20-120 seconds and shows a browser window. Phase 1 takes <50ms. Phase 2 takes 1-5s silently.

### Why not use Rhino/Duktape instead of WebView sandbox?
WebView is already available in CloudStream (NavigationEngine). Adding Rhino (~5MB) or Duktape (JNI) adds dependency complexity without benefit — the WebView sandbox achieves the same result.

### Why keep strategies at all if the sandbox handles everything?
Performance and debugging. Strategies provide fast paths for known formats and their logs help diagnose what format is currently active.

### What if the page has obfuscated `document.write`?
The JavaScript always calls `document.write(decodeURIComponent(escape(decrypted)))` in some form. Our polyfill captures ALL calls to `document.write`. Even if the method reference is obfuscated (e.g., `document[_add('0x3')]`), the actual `document.write` function is what gets invoked, and our polyfill intercepts it.
