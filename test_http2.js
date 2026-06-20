const http2 = require('http2');
const https = require('https');

const EMBED_URL = 'https://vidoba.org/embed-51kirhtklgfy.html';

function fetchUrlHttp1(url, headers = {}) {
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

function fetchUrlHttp2(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const client = http2.connect(parsedUrl.origin);
        
        client.on('error', (err) => reject(err));
        
        // HTTP2 headers must have lower-case keys, and :path, :method, :scheme, :authority
        const reqHeaders = {
            ':method': 'GET',
            ':path': parsedUrl.pathname + parsedUrl.search,
            ':scheme': 'https',
            ':authority': parsedUrl.hostname,
        };
        
        for (const [k, v] of Object.entries(headers)) {
            reqHeaders[k.toLowerCase()] = v;
        }
        
        const req = client.request(reqHeaders);
        
        let status = 0;
        let data = '';
        let respHeaders = {};
        
        req.on('response', (headers) => {
            status = headers[':status'];
            respHeaders = headers;
        });
        
        req.on('data', (chunk) => {
            data += chunk;
        });
        
        req.on('end', () => {
            client.close();
            resolve({ status, data, headers: respHeaders });
        });
        
        req.on('error', (err) => {
            client.close();
            reject(err);
        });
        
        req.end();
    });
}

async function run() {
    console.log("1. Fetching embed page via HTTP1...");
    const embedRes = await fetchUrlHttp1(EMBED_URL, {
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
    
    const requestHeaders = {
        "User-Agent": 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        "Referer": 'https://vidoba.org/'
    };
    
    console.log("\n--- Testing HTTP/1.1 Request to Variant URL ---");
    const resHttp1 = await fetchUrlHttp1(variantUrlWithToken, requestHeaders);
    console.log(`HTTP/1.1 Status: ${resHttp1.status}, Length: ${resHttp1.data.length}`);
    if (resHttp1.status === 403) {
        console.log("HTTP/1.1 Response:", resHttp1.data.slice(0, 150));
    }
    
    console.log("\n--- Testing HTTP/2 Request to Variant URL ---");
    try {
        const resHttp2 = await fetchUrlHttp2(variantUrlWithToken, requestHeaders);
        console.log(`HTTP/2 Status: ${resHttp2.status}, Length: ${resHttp2.data.length}`);
        if (resHttp2.status === 403) {
            console.log("HTTP/2 Response:", resHttp2.data.slice(0, 150));
        }
    } catch (e) {
        console.log("HTTP/2 request failed:", e.message);
    }
}

run();
