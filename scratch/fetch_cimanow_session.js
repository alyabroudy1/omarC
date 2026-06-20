const https = require('https');
const fs = require('fs');

const detailUrl = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/';
const watchUrl = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/watching/';

const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
};

function get(url, reqHeaders = {}) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: 'GET',
            headers: {
                ...headers,
                ...reqHeaders
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

function parseCookies(cookieHeaders) {
    if (!cookieHeaders) return '';
    return cookieHeaders.map(c => c.split(';')[0]).join('; ');
}

async function run() {
    console.log("[1] Fetching detail page to start session...");
    const detailRes = await get(detailUrl);
    console.log("Detail Status:", detailRes.status);
    const cookies = parseCookies(detailRes.headers['set-cookie']);
    console.log("Cookies collected:", cookies || "None");

    console.log("\n[2] Fetching watch page with referer and cookies...");
    const watchRes = await get(watchUrl, {
        'Referer': detailUrl,
        'Cookie': cookies
    });
    console.log("Watch Status:", watchRes.status);
    console.log("Watch Headers:", watchRes.headers);
    
    if (watchRes.status === 302 || watchRes.status === 301) {
        console.log("Redirect Location:", watchRes.headers.location);
    } else {
        console.log("HTML length:", watchRes.data.length);
        fs.writeFileSync('cimanow_watch_session.html', watchRes.data);
    }
}

run();
