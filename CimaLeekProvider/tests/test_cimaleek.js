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

// ==================== CimaLeek Decryption Multi-Strategy (Kotlin → JS) ====================

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

function xorDecrypt(quzs, kQqs, key) {
    const decodedBytes = Buffer.from(quzs, 'base64');
    const kopp = decodedBytes.toString('latin1');
    const gNks = key;
    let result = '';
    for (let i = 0; i < kopp.length; i++) {
        const gljp = kopp.charCodeAt(i);
        const immp = gNks.charCodeAt(i % gNks.length);
        const cidp = kQqs.charCodeAt(i % kQqs.length);
        const decryptedChar = gljp ^ immp ^ cidp;
        result += String.fromCharCode(decryptedChar);
    }
    return result;
}

const DECRYPTION_STRATEGIES = [
    {
        name: 'V2_CURRENT',
        key: '121af524a017cb96675243aa34cde44e',
        getLengths: (meta) => [meta.postLinkPathLength, meta.watchPathLength]
    },
    {
        name: 'V1_LEGACY',
        key: '9b09102b216d23cbb6cf75b47c82961c',
        getLengths: (meta) => [meta.watchPathLength, meta.postLinkPathLength, 0]
    }
];

function decryptServerPayload(payloadA, slicesB, keyC, meta, serverTag = '') {
    for (const strategy of DECRYPTION_STRATEGIES) {
        const candidateLengths = [...new Set(strategy.getLengths(meta))];
        for (const len of candidateLengths) {
            try {
                const cleaned = mdTq(payloadA, slicesB, len);
                const decrypted = xorDecrypt(cleaned, keyC, strategy.key);
                if (decrypted.startsWith('http')) {
                    console.log(`    [Strategy ${strategy.name}] (offset=${len}) SUCCESS -> ${decrypted.substring(0, 80)}...`);
                    return { success: true, strategy: strategy.name, url: decrypted };
                }
            } catch (e) {}
        }
    }
    return { success: false };
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

    // Step 2: Parse ver, post_id, and post_link
    console.log('\n[2/5] Parsing ver, post_id, and post_link...');
    const verMatch = html.match(/"ver":\s*"([^"]+)"/) || html.match(/'ver':\s*'([^']+)'/);
    const ver = verMatch ? verMatch[1] : '';
    const postIdMatch = html.match(/"post_id":\s*(\d+)/) || html.match(/"post_id":\s*"([^"]+)"/);
    const postId = postIdMatch ? postIdMatch[1] : '';
    const postLinkMatch = html.match(/"post_link":\s*"([^"]+)"/);
    const postLink = postLinkMatch ? postLinkMatch[1].replace(/\\\//g, '/') : watchUrl;
    console.log('  ver:', ver);
    console.log('  postId:', postId);
    console.log('  postLink:', postLink);

    // Step 3: Find server elements
    console.log('\n[3/5] Finding server elements (.lalaplay_player_option)...');
    const attrRegex = /class="[^"]*lalaplay_player_option[^"]*"[^>]*data-type="([^"]*)"[^>]*data-post="([^"]*)"[^>]*data-nume="([^"]*)"/g;
    let sMatch;
    const servers = [];
    while ((sMatch = attrRegex.exec(html)) !== null) {
        servers.push({ type: sMatch[1], post: sMatch[2] || postId, nume: sMatch[3] });
    }

    if (servers.length === 0) {
        console.log('  Searching data-type/data-post/data-nume without strict class order...');
        const altRegex = /data-type=['"]([^'"]*)['"][^>]*data-post=['"]([^'"]*)['"][^>]*data-nume=['"]([^'"]*)['"]/g;
        while ((sMatch = altRegex.exec(html)) !== null) {
            servers.push({ type: sMatch[1], post: sMatch[2] || postId, nume: sMatch[3] });
        }
    }

    servers.forEach((s, i) => console.log(`  Server ${i}: type=${s.type}, post=${s.post}, nume=${s.nume}`));

    if (servers.length === 0) {
        console.log('\n  FAIL: No server elements found!');
        return;
    }

    // Step 4: Prepare DecryptionMetadata
    console.log('\n[4/5] Preparing DecryptionMetadata...');
    const postLinkPath = new URL(postLink).pathname.replace(/^\/+|\/+$/g, '');
    const watchPath = new URL(watchUrl).pathname.replace(/^\/+|\/+$/g, '');
    const trimmedWatchPath = watchPath.endsWith('watch') ? watchPath.replace(/\/?watch\/?$/, '').replace(/^\/+|\/+$/g, '') : watchPath;
    const meta = {
        watchUrl,
        postLink,
        postId,
        ver,
        postLinkPathLength: postLinkPath.length,
        watchPathLength: trimmedWatchPath.length
    };
    console.log('  postLinkPathLength:', meta.postLinkPathLength);
    console.log('  watchPathLength:', meta.watchPathLength);

    // Step 5: Process each server
    console.log('\n[5/5] Processing servers...');
    for (const [idx, server] of servers.entries()) {
        console.log(`\n  --- Server ${idx}: ${server.type} (nume=${server.nume}) ---`);
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

            try {
                const json = JSON.parse(apiData);
                const a = json.a || '';
                const b = json.b || [];
                const c = json.c || '';

                console.log('  a (encrypted):', a.substring(0, 40) + '...');
                console.log('  b (ranges count):', b.length);
                console.log('  c (key):', c);

                const result = decryptServerPayload(a, b, c, meta, `[${idx}] ${server.type}`);
                if (result.success) {
                    console.log('  ✓ SUCCESS with Strategy:', result.strategy);
                    console.log('  ✓ Resolved URL:', result.url);
                } else {
                    console.log('  ✗ All decryption strategies failed');
                }
            } catch (e) {
                console.log('  JSON parse error:', e.message);
            }
        } catch (e) {
            console.log('  API request error:', e.message);
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
