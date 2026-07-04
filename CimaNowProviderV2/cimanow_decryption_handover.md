# CimaNow Watch Page Decryption Handover & Cheatsheet

This cheatsheet is written for developers and future AI agents to immediately understand, debug, and update the **CimaNow** decryption flow if the site changes its encryption parameters again.

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
