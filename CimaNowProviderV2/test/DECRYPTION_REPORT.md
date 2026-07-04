# CimaNow Decryption Analysis & Troubleshooting Guide

This directory contains test scripts and documentation detailing the mechanics of the CimaNow redirection bypass, watch page decryption, and link extraction.

---

## 1. Redirection & HMAC Token Sequence

To access the streaming servers, the client must simulate the browser redirection and countdown bypass:

1. **Freex2line Extraction:** Fetch the episode page HTML and extract the intermediate shortlink matching `https?://[^"']*freex2line[^"']*` (e.g. `https://freex2line.online/?link=base64...`).
2. **Intermediate Loadon:** Request the `freex2line` shortlink. Save any returned `Set-Cookie` session/security headers (e.g. Cloudflare or custom tracking cookies).
3. **Tracking Bypass:** Fetch `/redirectingfree/` with the original `freex2line` link set as the `Referer` header.
4. **Context Retrieval:** Fetch `/2020/02/blog-post.html` with `/redirectingfree/` set as the `Referer` header. Parse the returned HTML for:
   - Dynamic JavaScript variable names: `ch`, `ri`, `ke`, `se`.
   - The context dictionary containing the values for these variable names: `window['ctx_...'] = { ... }`.
5. **Decryption Key Generation:**
   - Decode base64 value of `ke`.
   - XOR each byte of decoded `ke` with characters from `se` in a round-robin cycle: `key_bytes[i] = ke_bytes[i] ^ se[i % se.length]`.
   - Convert the XORed bytes to a UTF-8 string to get the `key_hex`.
6. **HMAC Signature Token:**
   - Build the HMAC message: `msg = ri + ch + fp` where `fp = "TW96aWxsYS81Ll9f"` (Base64 of "Mozilla/5.__").
   - Compute `HMAC-SHA256` of `msg` using `key_hex` as the key.
   - Base64 encode the final HMAC hash to get `hmac_token`.
7. **Bypass Countdown:** Delay/wait for exactly **11 seconds** to satisfy the backend countdown trigger.
8. **Final URL Retrieval:** Fetch `/2020/02/blog-post.html/get-link.php` using query parameters:
   - `request_id = ri`
   - `hmac_token = URLEncode(hmac_token)`
   - `ch = ch`
   - `fp = fp`
   - Header `X-Requested-With: XMLHttpRequest`
   - Header `Referer: https://rm.freex2line.online/2020/02/blog-post.html/`
   - The response text contains the final watch page URL (`watch_url`).
9. **Watch Page Fetching:** Fetch the `watch_url` with the `Referer` set to `https://rm.freex2line.online/2020/02/blog-post.html/` to receive the encrypted watch page HTML.

---

## 2. Decrypting the Watch Page HTML

Once the watch page HTML is fetched, it must be decrypted to reveal the list of watch servers (iframes) and download links.

**NOTE:** The decryption format changes frequently (sometimes every few hours). The provider uses a **strategy-based architecture** — see `cimanow_decryption_handover.md` for full details on all known formats and the auto-detection pipeline.

### Current Active Format (verified July 4, 2026): AtobConfigStrategy

The watch page contains an inline script with:
```javascript
var _cfg = atob('MjUwMDAsNjgwMDAsMjc0MCwzNSwxNmY4ZTk=').split(',');
var _k = parseInt(_cfg[0]) + (parseInt(_cfg[4], 16) % parseInt(_cfg[1]));
var _pLoad = _ve0955.join('');  // array of *-separated base64 strings
var _chunks = _pLoad.split('*');
// per chunk: base64 → split '-' → parseInt(part[1], 35) → (n - 2740) XOR _k
```

Key = `25000 + (0x16f8e9 % 68000)` = `34513` · Base = 35 · Subtract = 2740 · Delimiter = `*`

---

## 3. Test Scripts

| Script | Purpose |
|--------|---------|
| `fetch_and_decrypt.py` | Full redirect chain + decryption (live). Fetches a movie, traverses the freex2line → HMAC → watch page pipeline, decrypts using auto-detection. Outputs raw watch HTML to `/tmp/` and decrypted HTML to `/tmp/`. |
| `decrypt_watch.py` | **Legacy** — decrypts old-format watch pages (`_oArr`/`_dk1`/`_dk2` keys, `@` delimiter). Kept for reference; will not work on current format. |
| `verify_rss_feed.py` | Tests RSS feed search endpoint for post ID fallback. |

### Running the Live Test
```bash
python3 CimaNowProviderV2/test/fetch_and_decrypt.py
```
This will:
1. Fetch the Enola Holmes 3 movie page
2. Follow the full redirect chain
3. Wait 12 seconds for countdown bypass
4. Fetch the encrypted watch page (~3MB)
5. Auto-detect and decrypt the format
6. Save decrypted HTML with server list

---

## 4. How to Debug & Fix When the Encryption Changes

If CimaNow changes their watch page encryption again, follow this checklist:

### First: Check if the JS Sandbox Still Works (it should)
The sandbox (Phase 2 of `decryptWatchHtml`) executes the original decryption JavaScript and is **immune to format changes**. If the sandbox works, the provider continues functioning even with a new format — you don't need to do anything urgent.

### If the Sandbox Fails Too
1. Check if the script structure changed fundamentally (e.g., now uses Web Worker, WASM, or network fetch for decryption)
2. Update the polyfills in `buildSandboxScript()` to handle new browser APIs
3. If the page now requires user interaction, the sandbox won't work — rely on Phase 3 WebView navigation

### To Add a New Fast Strategy (optimization)
If you want to add a strategy so Phase 1 can handle the new format quickly:

### Step 1: Run the Live Test
```bash
python3 CimaNowProviderV2/test/fetch_and_decrypt.py
```
Check the output — it will show which strategies detected the format, the derived key, and whether decryption succeeded.

### Step 2: Examine the Raw Watch HTML
The raw HTML is saved to `/tmp/cimanow_watch_raw.html`. Search it for:
- `atob(` calls → likely a parameter change in the AtobConfig format
- New variable prefixes or `var` declarations with large values
- New delimiter characters (`*`, `@`, `|`, or something new)
- Changed `parseInt` base values

### Step 3: Identify the Strategy Gap
Compare what the raw HTML contains vs. what each strategy's `canHandle()` checks for:
- **AtobConfigStrategy**: looks for `atob('BASE64')` with 5-field CSV config
- **Version D**: looks for `_pHsh` variable
- **Version C**: looks for `_x\d+` variables
- **Version B**: looks for `_oArr` array
- **Version A**: looks for `_dk1`/`_dk2`

### Step 4: Update or Add a Strategy
- If the AtobConfig format changed parameters only → update the config parsing
- If variable names changed → update the regex patterns in the relevant strategy
- If a completely new format → add a new strategy class and register it

### Step 5: Update this Document and the Handover
Record the new format details, parameters, and date observed in `cimanow_decryption_handover.md`.

---

## 5. Full Redirect Chain Test (Manual via curl)

If you need to test the redirect chain independently:
```bash
# 1. Get freex2line URL
curl -s 'https://cimanow.cc/فيلم-enola-holmes-3-2026-مترجم/' | grep -oP 'href="\Khttps?://[^"]*freex2line[^"]*'

# 2. Follow redirects manually with cookie capture
# (Use fetch_and_decrypt.py instead — handles cookies, HMAC, and the 12s wait)
```
