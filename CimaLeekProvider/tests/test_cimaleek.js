/**
 * Tests CimaLeek's loadLinks flow end-to-end: fetch watch page, call API, decrypt.
 * Replicates the Kotlin logic in Node.js for debugging.
 *
 * Usage: node test_cimaleek.js
 */

const https = require('https');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');

function fetch(url, options = {}) {
    return new Promise((resolve, reject) => {
        const mod = url.startsWith('https') ? https : http;
        const u = new URL(url);
        const opts = {
            hostname: u.hostname,
            path: u.pathname + u.search,
            method: options.method || 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
                ...options.headers,
            },
            rejectUnauthorized: false,
        };
        const req = mod.request(opts, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({
                text: data,
                status: res.statusCode,
                headers: res.headers,
            }));
        });
        req.on('error', reject);
        if (options.body) req.write(options.body);
        req.end();
    });
}

// ==================== CimaLeek Decryption (Kotlin → JS) ====================

function jsSlice(str, start, end) {
    const len = str.length;
    let actualStart = start < 0 ? len + start : start;
    if (actualStart < 0) actualStart = 0;
    if (actualStart > len) actualStart = len;
    let actualEnd = end === undefined ? len : (end < 0 ? len + end : end);
    if (actualEnd < 0) actualEnd = 0;
    if (actualEnd > len) actualEnd = len;
    if (actualStart >= actualEnd) return '';
    return str.substring(actualStart, actualEnd);
}

function mdTq(a, b, pathLength) {
    let kwDr = a;
    for (let i = b.length - 1; i >= 0; i--) {
        const range = b[i];
        if (range.length < 2) continue;
        const startVal = range[0];
        const cqrr = range[1];
        const start = startVal - pathLength;
        const sliced1 = jsSlice(kwDr, 0, start);
        const sliced2 = jsSlice(kwDr, cqrr, null);
        kwDr = sliced1 + sliced2;
    }
    return kwDr;
}

function decryptIOns(quzs, kQqs) {
    const decodedBytes = Buffer.from(quzs, 'base64');
    const kopp = decodedBytes.toString('latin1');
    const gNks = '9b09102b216d23cbb6cf75b47c82961c';
    let result = '';
    for (let i = 0; i < kopp.length; i++) {
        const gljp = kopp.charCodeAt(i);
        const immp = gNks.charCodeAt(i % 32);
        const cidp = kQqs.charCodeAt(i % kQqs.length);
        const decryptedChar = gljp ^ immp ^ cidp;
        result += String.fromCharCode(decryptedChar);
    }
    return result;
}

function generateRandomString(length) {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < length; i++) {
        result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
}

// ==================== Main Test ====================

const MAIN_URL = 'https://m.cimaleek.pw';

async function testCimaLeek(watchUrl) {
    console.log('=== CimaLeek loadLinks Test ===\n');

    // Step 1: Fetch watch page
    console.log('[1/5] Fetching watch page:', watchUrl);
    const watchRes = await fetch(watchUrl);
    const html = watchRes.text;
    console.log('  Status:', watchRes.status);
    console.log('  HTML length:', html.length);
    fs.writeFileSync('/tmp/cimaleek_watch.html', html);
    console.log('  Saved to /tmp/cimaleek_watch.html');

    // Step 2: Parse ver and post_id
    console.log('\n[2/5] Parsing ver and post_id...');
    const verMatch = html.match(/"""ver"\s*:\s*"([^"]+)"""/);
    const ver = verMatch ? verMatch[1] : null;
    const postIdMatch = html.match(/"""post_id"\s*:\s*(\d+)""/) || html.match(/"""post_id"\s*:\s*"([^"]+)"""/);
    const postId = postIdMatch ? postIdMatch[1] : null;
    console.log('  ver:', ver);
    console.log('  postId:', postId);

    // Step 3: Find server elements
    console.log('\n[3/5] Finding server elements (.lalaplay_player_option)...');
    // Use a simple regex to count occurrences since we can't use Jsoup easily
    const serverCount = (html.match(/class="[^"]*lalaplay_player_option[^"]*"/g) || []).length;
    console.log('  Found', serverCount, 'server option elements');

    // Also extract individual server data
    const serverRegex = /<div[^>]*class="[^"]*lalaplay_player_option[^"]*"[^>]*data-type="([^"]*)"[^>]*data-post="([^"]*)"[^>]*data-nume="([^"]*)"[^>]*>/g;
    let sMatch;
    const servers = [];
    while ((sMatch = serverRegex.exec(html)) !== null) {
        servers.push({ type: sMatch[1], post: sMatch[2] || postId, nume: sMatch[3] });
    }

    // Alternative: maybe the structure is different. Let's search for data-type/data-post/data-nume
    if (servers.length === 0) {
        console.log('  No servers found with standard regex. Searching for data attributes (single/double quotes)...');
        const attrRegex = /data-type=['"]([^'"]*)['"][^>]*data-post=['"]([^'"]*)['"][^>]*data-nume=['"]([^'"]*)['"]/g;
        while ((sMatch = attrRegex.exec(html)) !== null) {
            servers.push({ type: sMatch[1], post: sMatch[2] || postId, nume: sMatch[3] });
        }
        console.log('  Found', servers.length, 'servers via data attributes');
    }

    servers.forEach((s, i) => console.log(`  Server ${i}: type=${s.type}, post=${s.post}, nume=${s.nume}`));

    if (servers.length === 0) {
        console.log('\n  FAIL: No server elements found!');
        console.log('  Dumping context around "lalaplay"...');
        const idx = html.indexOf('lalaplay');
        if (idx >= 0) console.log(html.substring(Math.max(0, idx - 200), idx + 500));
        else {
            console.log('  "lalaplay" not found in HTML');
            // Search for any player/server related content
            const playerIdx = html.indexOf('player');
            if (playerIdx >= 0) console.log('  Context around "player":', html.substring(Math.max(0, playerIdx - 100), playerIdx + 300));
        }
        return;
    }

    // Step 4: Calculate pathLength
    console.log('\n[4/5] Calculating pathLength...');
    const urlObj = new URL(watchUrl);
    let path = urlObj.pathname.replace(/^\/+|\/+$/g, '');
    let trimmedPath = path.endsWith('watch') ? path.replace(/\/?watch\/?$/, '').replace(/^\/+|\/+$/g, '') : path;
    const pathLength = trimmedPath.length;
    console.log('  Path:', path);
    console.log('  Trimmed path:', trimmedPath);
    console.log('  pathLength:', pathLength);

    // Step 5: Process each server
    console.log('\n[5/5] Processing servers...');
    for (const [idx, server] of servers.entries()) {
        console.log(`\n  --- Server ${idx}: ${server.nume || 'unknown'} ---`);
        const rand = generateRandomString(16);
        const apiUrl = `${MAIN_URL}/wp-json/lalaplayer/v2/?p=${server.post}&t=${server.type}&n=${server.nume}&ver=${ver}&rand=${rand}`;
        console.log('  API URL:', apiUrl);

        try {
            const apiRes = await fetch(apiUrl, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K)',
                    'Referer': watchUrl,
                    'X-Requested-With': 'com.android.browser',
                }
            });
            console.log('  API status:', apiRes.status);
            const apiData = apiRes.text.trim();
            console.log('  API response:', apiData.substring(0, 200));

            try {
                const json = JSON.parse(apiData);
                const a = json.a || '';
                const b = json.b || [];
                const c = json.c || '';

                console.log('  a (encrypted):', a.substring(0, 50) + '...');
                console.log('  b (ranges):', JSON.stringify(b));
                console.log('  c (key):', c);

                // Decrypt
                const cleaned = mdTq(a, b, pathLength);
                console.log('  After mdTq:', cleaned.substring(0, 80));

                const decryptedUrl = decryptIOns(cleaned, c);
                console.log('  Decrypted URL:', decryptedUrl);

                if (decryptedUrl.startsWith('http')) {
                    console.log('  ✓ VALID URL!');
                } else if (decryptedUrl.length > 0) {
                    console.log('  ✗ NOT a URL (length=' + decryptedUrl.length + ')');
                    console.log('  Raw hex:', Buffer.from(decryptedUrl, 'latin1').toString('hex').substring(0, 100));
                } else {
                    console.log('  ✗ Empty decryption result');
                }
            } catch (e) {
                console.log('  ✗ JSON parse error:', e.message);
            }
        } catch (e) {
            console.log('  ✗ API request error:', e.message);
        }
    }
}

// ==================== Run ====================

// Try a sample movie URL
// From the provider, the watch URL is data + "/watch/"
// We need to first find a movie page. Let's try the homepage first.

async function findMovieUrl() {
    console.log('Finding a sample movie page...');
    const listRes = await fetch('https://m.cimaleek.pw/movies-list/');
    const html = listRes.text;
    const linkPattern = /href="(https?:\/\/m\.cimaleek\.pw\/movies\/[^"]+?)"/g;
    let m; const links = [];
    while ((m = linkPattern.exec(html)) !== null) links.push(m[1]);
    if (links.length > 0) return links[0];
    console.log('  Could not find movie URL on movies-list page');
    return null;
}

(async () => {
    const movieUrl = await findMovieUrl();
    if (!movieUrl) {
        console.log('Could not find a movie URL on homepage');
        return;
    }
    console.log('Found movie:', movieUrl, '\n');
    const watchUrl = movieUrl.endsWith('/') ? movieUrl + 'watch/' : movieUrl + '/watch/';
    await testCimaLeek(watchUrl);
})();
