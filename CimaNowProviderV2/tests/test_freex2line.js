const https = require('https');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');

function reMatch(text, pattern) {
    const re = new RegExp(pattern);
    const match = re.exec(text);
    return match ? match[1] : null;
}

function base64Decode(str) {
    return Buffer.from(str, 'base64');
}

function base64Encode(str) {
    return Buffer.from(str).toString('base64');
}

function fetch(url, options = {}) {
    return new Promise((resolve, reject) => {
        const mod = url.startsWith('https') ? https : http;
        const urlObj = new URL(url);
        const opts = {
            hostname: urlObj.hostname,
            path: urlObj.pathname + urlObj.search,
            method: 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
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
                cookies: res.headers['set-cookie'] || [],
            }));
        });
        req.on('error', reject);
        req.end();
    });
}

function calculateHmacSha256(message, secret) {
    return crypto.createHmac('sha256', secret).update(message).digest('base64');
}

async function resolveFreex2line(intermediateLink) {
    console.log('======= [STARTING RESOLVER] =======');
    const sessionCookies = {};
    const baseHeaders = {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
    };

    try {
        // Step 1
        console.log('[1/6] Initializing session...');
        const headRes = await fetch(intermediateLink, { headers: baseHeaders });
        console.log('  Status:', headRes.status, 'Length:', headRes.text.length);

        // Step 2
        console.log('[2/6] Fetching data page...');
        const pageUrl = 'https://rm.freex2line.online/2020/02/blog-post.html/';
        const res = await fetch(pageUrl, { headers: baseHeaders });
        let html = res.text;
        console.log('  Blog page length:', html.length, 'Has _0x_cfg:', html.includes('_0x_cfg'));

        if (!html.includes('_0x_cfg') && headRes.text.includes('_0x_cfg')) {
            console.log('  [FALLBACK] Using head response HTML');
            html = headRes.text;
        }

        fs.writeFileSync('/tmp/freex2line_debug.html', html);
        console.log('  HTML saved to /tmp/freex2line_debug.html');

        // Step 3: New format
        console.log('[3/6] Parsing challenge data...');
        const ptrMatch = reMatch(html, "window\\.ptr_\\w+\\s*=\\s*'([^']+)'");
        const ctxName = ptrMatch;
        let ch = null, requestId = null, encryptedKeyB64 = null, sXorKey = null;

        if (ctxName) {
            console.log('  Found ptr context name:', ctxName);
            const ctxJson = reMatch(html, `(?:window\\[)?['"]${ctxName}['"](?:\\])?\\s*=\\s*\\{([^}]+)\\}`);
            const mapMatch = reMatch(html, "window\\.map_\\w+\\s*=\\s*\\{([^}]+)\\}");

            if (ctxJson && mapMatch) {
                const chKey = reMatch(mapMatch, "ch:\\s*'([^']+)'");
                const riKey = reMatch(mapMatch, "ri:\\s*'([^']+)'");
                const keKey = reMatch(mapMatch, "ke:\\s*'([^']+)'");
                const seKey = reMatch(mapMatch, "se:\\s*'([^']+)'");

                if (chKey && riKey && keKey && seKey) {
                    ch = reMatch(ctxJson, `'?${chKey}'?\\s*:\\s*'([^']+)'`);
                    requestId = reMatch(ctxJson, `'?${riKey}'?\\s*:\\s*'([^']+)'`);
                    encryptedKeyB64 = reMatch(ctxJson, `'?${keKey}'?\\s*:\\s*'([^']+)'`);
                    sXorKey = reMatch(ctxJson, `'?${seKey}'?\\s*:\\s*'([^']+)'`);
                    console.log('  New format: ch=' + ch + ', ri=' + requestId + ', se=' + sXorKey);
                }
            }
        }

        // Fallback: _0x_cfg
        if (!ch || !requestId || !encryptedKeyB64 || !sXorKey) {
            console.log('  Trying _0x_cfg fallback...');
            const cfgText = reMatch(html, "(?:var|let|const|window\\.)?\\s*_0x_cfg\\s*=\\s*\\{([^}]+)\\}");
            if (cfgText) {
                if (!ch) ch = reMatch(cfgText, "'?c'?:\\s*'([^']+)'");
                if (!requestId) requestId = reMatch(cfgText, "'?r'?:\\s*'([^']+)'");
                if (!encryptedKeyB64) encryptedKeyB64 = reMatch(cfgText, "'?k'?:\\s*'([^']+)'");
                if (!sXorKey) sXorKey = reMatch(cfgText, "'?s'?:\\s*'([^']+)'");
                console.log('  _0x_cfg: ch=' + ch + ', ri=' + requestId);
            }
        }

        if (!ch) throw new Error('ch value not found');
        if (!requestId) throw new Error('requestId value not found');
        if (!encryptedKeyB64) throw new Error('Encrypted key value not found');
        if (!sXorKey) throw new Error('s (XOR key) not found');
        console.log('  Final values: ch=' + ch + ', requestId=' + requestId + ', sXorKey=' + sXorKey);

        // Step 5: Decrypt
        console.log('[5/6] Decrypting secret key...');
        const encryptedBytes = base64Decode(encryptedKeyB64);
        const decryptedChars = [];
        for (let i = 0; i < encryptedBytes.length; i++) {
            decryptedChars.push(String.fromCharCode(encryptedBytes[i] ^ sXorKey.charCodeAt(i % sXorKey.length)));
        }
        const secretKey = decryptedChars.join('');
        console.log('  Secret Key:', secretKey);

        // Step 6: HMAC
        console.log('[6/6] Generating HMAC...');
        const fpRaw = 'Mozilla/5.10';
        const fpBase64 = base64Encode(fpRaw);
        const messageToSign = requestId + ch + fpBase64;
        const hmacToken = calculateHmacSha256(messageToSign, secretKey);
        const hmacTokenEncoded = encodeURIComponent(hmacToken);
        console.log('  HMAC:', hmacToken);

        // Step 7: API request
        console.log('[Sending API request...]');
        const apiUrl = `https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=${requestId}&hmac_token=${hmacTokenEncoded}&ch=${ch}&fp=${fpBase64}`;
        console.log('  Waiting 10s...');
        await new Promise(r => setTimeout(r, 10000));

        const finalRes = await fetch(apiUrl, { headers: baseHeaders });
        let result = finalRes.text;
        console.log('  Raw response:', JSON.stringify(result));
        result = result.replace(/^\ufeff/, '').trim();
        console.log('  Cleaned response:', result);

        if (result.startsWith('http')) {
            console.log('\n[SUCCESS]', result);
            return result;
        }
        console.log('\n[FAILURE] Response:', result);
        return null;
    } catch (e) {
        console.error('\n[FATAL ERROR]', e.message);
        return null;
    }
}

// Test
const linkB64 = 'aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4MSVkOSU4YSVkOSU4NCVkOSU4NS1taXNzaW9uLWltcG9zc2libGUtdGhlLWZpbmFsLXJlY2tvbmluZy0yMDI1LSVkOSU4NSVkOCVhYSVkOCViMSVkOCVhYyVkOSU4NS93YXRjaGluZy8=';
const testUrl = 'https://rm.freex2line.online/loadon/?link=' + linkB64;

resolveFreex2line(testUrl).then(r => console.log('\nFINAL:', r || 'FAILED'));
