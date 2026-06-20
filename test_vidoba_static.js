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
    const headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://larozza.casa/'
    };
    
    const embedRes = await fetchUrl(EMBED_URL, headers);
    console.log("Embed Page Status:", embedRes.status);
    
    // Use eval to safely get the unpacked string
    let d = embedRes.data;
    const startIndex = d.indexOf('eval(function(p,a,c,k,e,d)');
    if (startIndex === -1) {
        console.log("Could not find packed script.");
        return;
    }
    
    let packedStr = d.substring(startIndex);
    let endIndex = packedStr.indexOf('}))');
    if (endIndex === -1) endIndex = packedStr.indexOf('})()');
    if (endIndex === -1) {
        console.log("Could not find end of packed script.");
        return;
    }
    
    packedStr = packedStr.substring(0, endIndex + 3);
    
    // Replace eval with a self-executing function that returns the string
    const unpackerFunc = `(function() { return ${packedStr.replace(/^eval/, '')} })();`;
    
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
    
    console.log("\n3. Testing M3U8 Fetch with SAME Client (NodeJS)...");
    const resA = await fetchUrl(m3u8Url, {
        'User-Agent': headers['User-Agent'],
        'Referer': 'https://vidoba.org/'
    });
    
    console.log(`Status: ${resA.status}, Valid M3U8: ${resA.data.includes('#EXTM3U')}`);
    if (resA.status === 403) console.log("Response:", resA.data.slice(0, 100));
}

run();
