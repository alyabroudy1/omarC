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

Once the watch page HTML is fetched, it must be decrypted to reveal the list of watch servers (iframes) and download links:

### 2.1 Key Derivation
The website has two possible key derivation mechanisms (one active, one decoy):
- **Active Key (`_oArr` Sum):** Check if `var _oArr = [ ... ]` is defined in the scripts. If present, the key is the sum of all elements inside the array (e.g. `39597 + 39598 + 39597 = 118792`).
- **Decoy Key (`_dk1 - _dk2`):** Check if `var _dk1 = ...` and `var _dk2 = ...` are defined. The key is `_dk1 - _dk2`. (Currently, this is used as a decoy to break generic scrapers).

### 2.2 Extraction & XOR Decoding
1. Scan the HTML page for the obfuscated variable name containing the `@` separator (e.g. `var _b5178 = 'base64@base64@...';`).
2. Concatenate all string literals for that variable.
3. Split the concatenated string by `@`.
4. For each non-empty segment:
   - Decode from base64.
   - Retain only numeric (digit) characters.
   - Parse the digits as an integer.
   - XOR the integer with the derived `key`.
   - Convert the XORed integer to an ASCII character.
5. Decode the final Latin1 stream as a standard UTF-8 string to reveal the final watch page DOM!

---

## 3. How to Debug & Fix When the Encryption Changes

If CimaNow changes their watch page encryption again, follow this checklist to diagnose and resolve:

### Step 1: Capture a Fresh HAR Log
Open the website in your browser with Developer Tools enabled (F12), navigate to any movie/episode page, start playing the video, and save the network log as a `.har` file.

### Step 2: Search for the Obfuscation Script
Extract the decrypted watch page source (from the `/watching/` entry in the HAR file) and look for the inline scripts.
- Check the variable names for the keys (currently `_oArr`, `_dk1`, `_dk2`).
- If they renamed `_oArr`, update the regex inside [decryptWatchHtml](file:///Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2/omarC/CimaNowProviderV2/src/main/kotlin/com/cimanow/CimaNowProvider.kt#L1087-L1173):
  ```kotlin
  val oArrMatcher = Pattern.compile("var\\s+NEW_NAME\\s*=\\s*\\[([\\d,\\s]+)\\]").matcher(html)
  ```

### Step 3: Run the Local Decryption Script
Use the provided python script to test decryption locally against the raw watch HTML page:
```bash
python3 CimaNowProviderV2/test/decrypt_watch.py <path_to_raw_watch_page.html>
```
If it succeeds, it will print the derived key and output the decrypted DOM structure (which should contain `<li data-index="..." data-id="...">` tags).

### Step 4: Examine the Fallback Flow
If the programmatic decryption flow fails on the client due to a Cloudflare security policy update, check the **WebView Fallback Flow**. The WebView flow is fully resilient because it does not attempt to decrypt the page itself; it loads the watch page inside a background headless WebView, waits for the browser to decrypt it naturally, and runs a Javascript scraper to extract the rendered server items directly from the final DOM!
