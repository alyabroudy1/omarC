const https = require('https');

const EMBED_URL = 'https://vidoba.org/embed-51kirhtklgfy.html';

function fetchUrl(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: 'GET',
            headers: headers
        };
        const req = https.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ status: res.statusCode, data, headers: res.headers }));
        });
        req.on('error', reject);
        req.end();
    });
}

async function run() {
    console.log("1. Fetching embed page...");
    const embedRes = await fetchUrl(EMBED_URL, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://larozza.casa/'
    });
    
    let d = embedRes.data;
    const startIndex = d.indexOf('eval(function(p,a,c,k,e,d)');
    if (startIndex === -1) {
        console.log("Could not find packed script.");
        return;
    }
    
    let packedStr = d.substring(startIndex);
    const match = packedStr.match(/eval\(function\(p,a,c,k,e,d\).+?split\(\s*['"]\|['"]\s*\)\s*\)\s*\)/s);
    if (!match) {
        console.log("Could not find packed script.");
        return;
    }
    
    const packedCode = match[0];
    const unpackerFunc = `(function() { return ${packedCode.replace(/^eval/, '')} })();`;
    let unpacked = eval(unpackerFunc);
    
    const urlMatch = unpacked.match(/file:\s*["'](https:\/\/[^"']+\.m3u8[^"']*)["']/i);
    const m3u8Url = urlMatch[1];
    
    // Fetch Master M3U8
    const masterRes = await fetchUrl(m3u8Url, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org/'
    });
    
    const lines = masterRes.data.split('\n');
    let variantUrlRaw = '';
    for (const line of lines) {
        if (line.trim() && !line.startsWith('#')) {
            variantUrlRaw = line.trim();
            break;
        }
    }
    
    let variantUrl = variantUrlRaw;
    if (!variantUrlRaw.startsWith('http')) {
        const masterParsed = new URL(m3u8Url);
        const basePath = masterParsed.pathname.substring(0, masterParsed.pathname.lastIndexOf('/') + 1);
        variantUrl = masterParsed.origin + basePath + variantUrlRaw + masterParsed.search;
    }
    console.log("Resolved Variant URL:", variantUrl);
    
    // Test variant URL with different User-Agents
    const headersToTest = [
        // Test 1: Exact same User-Agent used to fetch the token
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        },
        // Test 2: Different browser User-Agent (Firefox)
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; rv:109.0) Gecko/20100101 Firefox/115.0'
        },
        // Test 3: Android system player User-Agent (stagefright)
        {
            'User-Agent': 'stagefright/1.2 (Linux;Android 14)'
        },
        // Test 4: Empty / No User-Agent
        {
        }
    ];
    
    for (let i = 0; i < headersToTest.length; i++) {
        const headers = headersToTest[i];
        console.log(`\n--- Test ${i+1}: UA: ${headers['User-Agent'] || 'none'} ---`);
        try {
            const res = await fetchUrl(variantUrl, headers);
            console.log(`Status: ${res.status}, Content Length: ${res.data.length}`);
            if (res.status === 403) {
                console.log("403 Response:", res.data.slice(0, 150));
            }
        } catch(e) {
            console.log("Request failed:", e.message);
        }
    }
}

run();
