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
    
    console.log("Embed Page Status:", embedRes.status);
    
    let d = embedRes.data;
    const startIndex = d.indexOf('eval(function(p,a,c,k,e,d)');
    if (startIndex === -1) {
        console.log("Could not find packed script.");
        return;
    }
    
    let packedStr = d.substring(startIndex);
    // Find matching eval end by regex or indexOf
    const match = packedStr.match(/eval\(function\(p,a,c,k,e,d\).+?split\(\s*['"]\|['"]\s*\)\s*\)\s*\)/s);
    if (!match) {
        console.log("Could not find complete packed script using regex.");
        return;
    }
    
    const packedCode = match[0];
    
    // Replace eval with parenthesis to make it an expression we can evaluate
    const unpackerFunc = `(function() { return ${packedCode.replace(/^eval/, '')} })();`;
    
    let unpacked = "";
    try {
        unpacked = eval(unpackerFunc);
    } catch(e) {
        console.log("Eval failed", e);
        return;
    }
    
    const urlMatch = unpacked.match(/file:\s*["'](https:\/\/[^"']+\.m3u8[^"']*)["']/i);
    if (!urlMatch) {
        console.log("Could not find M3U8 in unpacked script.");
        return;
    }
    
    const m3u8Url = urlMatch[1];
    console.log("2. Extracted M3U8 URL:", m3u8Url);
    
    const headersToTest = [
        // 1. Referer = https://vidoba.org/
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://vidoba.org/'
        },
        // 2. Referer = https://vidoba.org
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://vidoba.org'
        },
        // 3. Referer = https://vidoba.org/embed-51kirhtklgfy.html (the exact embed URL!)
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': EMBED_URL
        },
        // 4. Referer = https://larozza.casa/ (the parent/origin page referer!)
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://larozza.casa/'
        },
        // 5. Origin = https://vidoba.org + Referer = https://vidoba.org/
        {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://vidoba.org/',
            'Origin': 'https://vidoba.org'
        }
    ];

    for (let i = 0; i < headersToTest.length; i++) {
        const headers = headersToTest[i];
        console.log(`\n--- Test ${i+1}: Referer: ${headers.Referer}, Origin: ${headers.Origin || 'none'} ---`);
        try {
            const res = await fetchUrl(m3u8Url, headers);
            console.log(`Status: ${res.status}, Valid M3U8: ${res.data.includes('#EXTM3U')}`);
            if (res.status === 403) {
                console.log("Response (first 100 chars):", res.data.slice(0, 100));
            }
        } catch(e) {
            console.log("Request failed:", e.message);
        }
    }
}

run();
