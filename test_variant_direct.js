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
    
    const masterParsed = new URL(m3u8Url);
    const relativePath = '../51kirhtklgfy_l/index-v1-a1.m3u8';
    const variantUrlObj = new URL(relativePath, m3u8Url);
    const variantUrlWithToken = variantUrlObj.href + masterParsed.search;
    
    console.log("Fetching variant playlist...");
    const variantRes = await fetchUrl(variantUrlWithToken, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org/'
    });
    
    // Find first TS segment in variant playlist
    const lines = variantRes.data.split('\n');
    let tsFile = '';
    for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed && !trimmed.startsWith('#') && trimmed.startsWith('http')) {
            tsFile = trimmed;
            break;
        }
    }
    
    if (!tsFile) {
        console.log("Could not find TS URL in playlist.");
        return;
    }
    
    console.log("TS URL:", tsFile);
    
    console.log("\n--- Test A: Requesting TS Segment with BASE DOMAIN Referer (https://vidoba.org/) ---");
    const resA = await fetchUrl(tsFile, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org/'
    });
    console.log(`Status A: ${resA.status}, Length: ${resA.data.length}`);
    
    console.log("\n--- Test B: Requesting TS Segment with FULL EMBED Referer (https://vidoba.org/embed-51kirhtklgfy.html) ---");
    const resB = await fetchUrl(tsFile, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': EMBED_URL
    });
    console.log(`Status B: ${resB.status}, Length: ${resB.data.length}`);
    if (resB.status === 403) {
        console.log("Response B:", resB.data.slice(0, 150));
    }
}

run();
