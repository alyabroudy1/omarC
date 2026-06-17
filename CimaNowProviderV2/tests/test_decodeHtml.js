/**
 * Tests the decodeHtml logic against a live watch page.
 * Replicates the Kotlin decodeAndWriteFast + decodeHtml algorithm in Node.js.
 *
 * Usage: node test_decodeHtml.js
 */

const https = require('https');
const http = require('http');
const fs = require('fs');

// ==================== decodeHtml (Kotlin port) ====================

function decodeAndWriteFast(chunk, key, outBytes) {
    const r = chunk.length % 4;
    if (r > 0) {
        if (r === 2) chunk += '==';
        else if (r === 3) chunk += '=';
    }
    try {
        const bytes = Buffer.from(chunk, 'base64');
        let num = 0n;
        for (const b of bytes) {
            const bInt = b;
            if (bInt >= 48 && bInt <= 57) {
                num = num * 10n + BigInt(bInt - 48);
            }
        }
        if (num > 0n) {
            outBytes.push(Number(num - BigInt(key)) & 0xFF);
            return 1;
        }
        return 0;
    } catch {
        return 0;
    }
}

function decodeHtml(rawHtml) {
    const keyMatch = rawHtml.match(/var\s+_r\s*=\s*(\d+(?:\+\d+)*)/);
    if (!keyMatch) {
        console.log('  [_r not found]');
        return rawHtml;
    }
    const dynamicKey = keyMatch[1].split('+').reduce((a, b) => a + parseInt(b, 10), 0);
    console.log('  _r expression:', keyMatch[1], '=> key:', dynamicKey);

    // Collect all base64-like strings (20+ chars in quotes)
    const dataRegex = /['"]([A-Za-z0-9+/=~]{20,})['"]/g;
    let extractedData = '';
    let match;
    while ((match = dataRegex.exec(rawHtml)) !== null) {
        extractedData += match[1];
    }
    console.log('  Extracted base64 data length:', extractedData.length);

    if (!extractedData) return rawHtml;

    const outBytes = [];
    let chunk = '';
    for (let i = 0; i < extractedData.length; i++) {
        const c = extractedData[i];
        if (c === '~') {
            if (chunk) {
                decodeAndWriteFast(chunk, dynamicKey, outBytes);
                chunk = '';
            }
        } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                   (c >= '0' && c <= '9') || c === '+' || c === '/' || c === '=') {
            chunk += c;
        }
    }
    if (chunk) {
        decodeAndWriteFast(chunk, dynamicKey, outBytes);
    }

    const decoded = Buffer.from(outBytes).toString('utf-8');
    console.log('  Decoded length:', decoded.length);
    console.log('  Decoded preview:', decoded.substring(0, 200));
    return decoded;
}

// ==================== Main ====================

// Use the watch page URL from the log
const watchUrl = 'https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-one-battle-after-another-2025-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/watching/?token=197f3d6909acda9a967f6882e4b4aa82af32ba235f7e435b2561b77604f548e6';

async function fetch(url) {
    return new Promise((resolve, reject) => {
        const mod = url.startsWith('https') ? https : http;
        const u = new URL(url);
        const opts = {
            hostname: u.hostname,
            path: u.pathname + u.search,
            method: 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
                'Referer': 'https://cimanow.cc/',
            },
            rejectUnauthorized: false,
        };
        const req = mod.request(opts, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(data));
        });
        req.on('error', reject);
        req.end();
    });
}

(async () => {
    console.log('Fetching watch page...');
    const rawHtml = await fetch(watchUrl);
    console.log('Raw page length:', rawHtml.length);
    fs.writeFileSync('/tmp/watch_raw.html', rawHtml);

    console.log('\nDecoding HTML...');
    const decoded = decodeHtml(rawHtml);
    fs.writeFileSync('/tmp/watch_decoded.html', decoded);

    console.log('\n=== Searching decoded output for server structures ===');
    const searches = [
        'data-index', 'data-id', 'ul#watch', 'li[data-index]',
        'server', 'download', 'watch', 'iframe', 'player',
        'data-server', 'data-link',
    ];
    for (const term of searches) {
        const count = (decoded.match(new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')) || []).length;
        if (count > 0) console.log(`  "${term}": ${count} occurrences`);
    }

    if (!decoded.includes('data-index')) {
        console.log('\nNo data-index found. Looking for alternative patterns...');
        // Show all <ul> and <li> structures
        const ulMatches = decoded.match(/<ul[^>]*>/gi);
        const liMatches = decoded.match(/<li[^>]*>/gi);
        console.log(`  <ul> tags: ${ulMatches ? ulMatches.length : 0}`);
        console.log(`  <li> tags: ${liMatches ? liMatches.length : 0}`);

        // Look for sections that might contain server info
        const serverSections = decoded.match(/[^.]*?(?:سيرفر|server|جودة|quality|تحميل|download)[^.]*\./gi);
        if (serverSections) {
            console.log('  Server-related text sections:');
            serverSections.slice(0, 5).forEach((s, i) => console.log(`    [${i}] ${s.substring(0, 150)}`));
        }

        // Find any elements with id containing "watch" or "download"
        const idMatches = decoded.match(/id\s*=\s*["'][^"']*["']/gi);
        if (idMatches) {
            const relevantIds = idMatches.filter(id =>
                /watch|download|server|serv|episode|film|movie/i.test(id));
            if (relevantIds.length > 0) {
                console.log('  Relevant element IDs:');
                relevantIds.slice(0, 10).forEach(id => console.log(`    ${id}`));
            }
        }
    }
})();
