# Extractor Test Scripts

This directory contains standalone scripts to test and debug Cloudstream extractor logic outside of the Android environment. This is useful for quickly verifying if a provider's HTML structure or API has changed without needing to recompile the app.

## Available Scripts

### Savefiles Extractor (`savefiles_test.js`)
Tests the extraction logic for `savefiles.com`.
- Simulates the POST request to bypass the interstitial page.
- Checks for both direct regex extraction and packed JavaScript.
- Validates the Base64 decoding fallback logic.
- **Usage**: `node savefiles_test.js`

### Savefiles Puppeteer Test (`savefiles_puppeteer_test.js`)
An advanced test for `savefiles.com` using a real headless browser.
- Useful when Cloudflare blocks standard HTTP requests (like `curl` or `axios`).
- Emulates a real user agent and waits for cloudflare challenges.
- Dumps the HTML and takes a screenshot for visual debugging.
- **Usage**: `npm install puppeteer && node savefiles_puppeteer_test.js`

## How to use
1. Make sure you have Node.js installed.
2. If scripts use external libraries (like `puppeteer`), run `npm install <package>` in the root directory first.
3. Run the script using `node test_extractors/<script_name.js>`.
4. The scripts will output the raw extracted video URL, and the final decoded/playable URL just as the Android extractor would.
