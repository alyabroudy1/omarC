const https = require('https');

const EMBED_URL = 'https://vidoba.org/embed-51kirhtklgfy.html';

function fetchUrl(url, headers = {}) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ status: res.statusCode, data, headers: res.headers }));
        }).on('error', reject);
    });
}

async function run() {
    console.log("1. Fetching embed page...");
    const embedRes = await fetchUrl(EMBED_URL, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Referer': 'https://larozza.casa/'
    });
    
    console.log("Embed Page Status:", embedRes.status);
    
    // Extract M3U8 URL from JWPlayer config
    const match = embedRes.data.match(/file:\s*["'](https:\/\/[^"']+\.m3u8[^"']*)["']/i);
    if (!match) {
        console.log("Could not find M3U8 in embed page. Maybe obfuscated?");
        return;
    }
    
    const m3u8Url = match[1];
    console.log("2. Extracted M3U8 URL:", m3u8Url);
    
    console.log("\n3. Testing M3U8 Fetch with DIFFERENT Referers...");
    
    // Test A: Exact Referer (embed page)
    console.log("\n--- Test A: Referer = Embed Page ---");
    const resA = await fetchUrl(m3u8Url, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Referer': EMBED_URL
    });
    console.log(`Status: ${resA.status}, Valid M3U8: ${resA.data.includes('#EXTM3U')}`);
    if (resA.status === 403) console.log("Response:", resA.data.slice(0, 100));

    // Test B: Root Referer (what SnifferExtractor sends)
    console.log("\n--- Test B: Referer = vidoba.org/ ---");
    const resB = await fetchUrl(m3u8Url, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Referer': 'https://vidoba.org/'
    });
    console.log(`Status: ${resB.status}, Valid M3U8: ${resB.data.includes('#EXTM3U')}`);
    if (resB.status === 403) console.log("Response:", resB.data.slice(0, 100));
}

run();
