const https = require('https');
const fs = require('fs');
const jsdom = require('jsdom');

const watchUrl = 'https://m.cimaleek.pw/movies/venom-the-last-dance-889877/watch/';

function fetchUrl(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                ...headers
            }
        };

        https.get(options, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                resolve({ status: res.statusCode, headers: res.headers, data: data });
            });
        }).on('error', reject);
    });
}

function MdTq(MxGr, ozJr, IuAr) {
    let kwDr = MxGr;
    for (let i = ozJr.length - 1; i >= 0; i--) {
        const Aoor = ozJr[i][0];
        const cqrr = ozJr[i][1];
        const start = Aoor - IuAr;
        kwDr = kwDr.slice(0, start) + kwDr.slice(cqrr);
    }
    return kwDr;
}

function decrypt(encryptedText, b, c, IuAr) {
    const cleaned = MdTq(encryptedText, b, IuAr);
    const decoded = Buffer.from(cleaned, 'base64').toString('binary');
    const key1 = "9b09102b216d23cbb6cf75b47c82961c";
    const key2 = c;
    
    let result = "";
    for (let i = 0; i < decoded.length; i++) {
        const charCode = decoded.charCodeAt(i);
        const k1Char = key1.charCodeAt(i % 32);
        const k2Char = key2.charCodeAt(i % key2.length);
        const decryptedChar = charCode ^ k1Char ^ k2Char;
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

async function runTest() {
    console.log(`[1] Fetching watch page: ${watchUrl}`);
    const pageRes = await fetchUrl(watchUrl);
    if (pageRes.status !== 200) {
        console.error(`Failed to fetch watch page, status: ${pageRes.status}`);
        return;
    }

    const dom = new jsdom.JSDOM(pageRes.data);
    const doc = dom.window.document;

    // Parse ver and post_id from the html
    const verMatch = pageRes.data.match(/"ver"\s*:\s*"([^"]+)"/);
    const ver = verMatch ? verMatch[1] : '';
    
    let postId = '';
    const postIdMatch1 = pageRes.data.match(/"post_id"\s*:\s*(\d+)/);
    const postIdMatch2 = pageRes.data.match(/"post_id"\s*:\s*"([^"]+)"/);
    if (postIdMatch1) postId = postIdMatch1[1];
    else if (postIdMatch2) postId = postIdMatch2[1];

    console.log(`Parsed watch parameters: ver='${ver}', postId='${postId}'`);

    // Path length calculation
    const parsedUrl = new URL(watchUrl);
    let path = parsedUrl.pathname.replace(/^\/|\/$/g, "");
    let trimmedPath = path.endsWith("watch") ? path.substring(0, path.lastIndexOf("watch")).replace(/^\/|\/$/g, "") : path;
    const pathLength = trimmedPath.length;
    console.log(`Trimmed path: '${trimmedPath}', length: ${pathLength}`);

    // Select server elements
    const servers = doc.querySelectorAll('.lalaplay_player_option');
    console.log(`Found ${servers.length} server options`);

    for (let i = 0; i < servers.length; i++) {
        const server = servers[i];
        const serverName = server.textContent.trim();
        const dataType = server.getAttribute('data-type') || '';
        const dataPost = server.getAttribute('data-post') || postId;
        const dataNume = server.getAttribute('data-nume') || '';

        if (!dataType || !dataPost || !dataNume) continue;

        console.log(`\n--- Processing server [${i+1}]: ${serverName} ---`);

        const rand = generateRandomString(16);
        const apiUrl = `https://m.cimaleek.pw/wp-json/lalaplayer/v2/?p=${dataPost}&t=${dataType}&n=${dataNume}&ver=${ver}&rand=${rand}`;
        
        console.log(`Fetching player JSON: ${apiUrl}`);
        const apiRes = await fetchUrl(apiUrl, {
            'Referer': watchUrl,
            'X-Requested-With': 'com.android.browser'
        });

        if (apiRes.status !== 200) {
            console.error(`API request failed with status: ${apiRes.status}`);
            continue;
        }

        let json;
        try {
            json = JSON.parse(apiRes.data);
        } catch (e) {
            console.error("Failed to parse API response as JSON:", apiRes.data);
            continue;
        }

        const a = json.a || '';
        const b = json.b || [];
        const c = json.c || '';

        if (!a) {
            console.error(`Invalid JSON fields: a='${a}', b='${b}', c='${c}'`);
            continue;
        }

        // Decrypt
        const decryptedUrl = decrypt(a, b, c, pathLength);
        console.log(`Decrypted URL: ${decryptedUrl}`);

        if (decryptedUrl.startsWith('http') && decryptedUrl.includes('.html')) {
            console.log(`Fetching wrapper page to test WrapperExtractor logic: ${decryptedUrl}`);
            
            const wrapperRes = await fetchUrl(decryptedUrl, {
                'Referer': watchUrl
            });

            if (wrapperRes.status !== 200) {
                console.error(`Failed to fetch wrapper page, status: ${wrapperRes.status}`);
                continue;
            }

            const wrapperDom = new jsdom.JSDOM(wrapperRes.data);
            const wrapperDoc = wrapperDom.window.document;

            // Try to find the iframe source
            const iframe = wrapperDoc.querySelector('iframe#embedr') || wrapperDoc.querySelector('iframe');
            const iframeSrc = iframe ? iframe.getAttribute('src') : null;

            if (iframeSrc) {
                console.log(`SUCCESS! Extracted wrapped iframe src: ${iframeSrc}`);
            } else {
                console.warn(`WARNING: No iframe found on wrapper page. HTML snippet: ${wrapperRes.data.substring(0, 300)}`);
            }
        } else {
            console.log(`Decrypted URL is not a wrapper HTML file or doesn't start with http.`);
        }
    }
}

runTest();
