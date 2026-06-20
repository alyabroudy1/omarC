const https = require('https');
const url = 'https://vroba-store1-lgk.cdnz.quest/hls2/04/00087/51kirhtklgfy_,l,n,.urlset/master.m3u8?t=299vZlMQQ4-RjVyyIZ9jMV6unLv8ZKu5XFsBV-Xyy9I&s=1781372696&e=43200&v=198830&i=0.3&sp=0';

function makeRequest(testName, headers) {
    return new Promise((resolve) => {
        const options = {
            headers: headers
        };
        const req = https.get(url, options, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                const isValid = data.includes('#EXTM3U');
                console.log(`[${testName}] Status: ${res.statusCode}, Valid M3U8: ${isValid}, Length: ${data.length}`);
                if (res.statusCode !== 200 || !isValid) {
                    console.log(`  Response: ${data.slice(0, 150)}`);
                }
                resolve();
            });
        });
        req.on('error', (e) => {
            console.log(`[${testName}] Error: ${e.message}`);
            resolve();
        });
    });
}

async function runTests() {
    // 1. Exact browser headers
    await makeRequest('1. Browser Headers', {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
        'Origin': 'https://vidoba.org',
        'Referer': 'https://vidoba.org/',
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'cross-site'
    });

    // 2. Android WebView User-Agent
    await makeRequest('2. Android WebView UA', {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36',
        'Accept': '*/*',
        'Origin': 'https://vidoba.org',
        'Referer': 'https://vidoba.org/',
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'cross-site'
    });

    // 3. Android WebView UA + X-Requested-With
    await makeRequest('3. Android WebView UA + X-Requested-With', {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36',
        'Accept': '*/*',
        'Origin': 'https://vidoba.org',
        'Referer': 'https://vidoba.org/',
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'cross-site',
        'X-Requested-With': 'com.lagradost.cloudstream3'
    });

    // 4. Default OkHttp/ExoPlayer headers (No Origin, no Sec-Fetch, standard UA/Referer)
    await makeRequest('4. Standard ExoPlayer Headers', {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36',
        'Referer': 'https://vidoba.org/'
    });

    // 5. Mismatched sec-ch-ua / platform
    await makeRequest('5. Mismatched Chrome UA with sec-ch-ua headers', {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
        'Origin': 'https://vidoba.org',
        'Referer': 'https://vidoba.org/',
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Sec-Fetch-Site': 'cross-site',
        'sec-ch-ua': '"Not(A:Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"',
        'sec-ch-ua-mobile': '?1',
        'sec-ch-ua-platform': 'Android'
    });
}

runTests();
