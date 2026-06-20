const https = require('https');
const fs = require('fs');

const url1 = 'https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC0lZDklODYlZDklODglZDklODYtJWQ4JWE3JWQ5JTg0JWQ5JTg2JWQ4JWIzJWQ5JTg4JWQ4JWE5LSVkOCVhNyVkOSU4NCVkOCVhNyVkOSU4OCVkOSU4NCVkOSU4YS93YXRjaGluZy8=';
const url2 = 'https://rm.freex2line.online/redirectingfree/';

const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': 'https://cimanow.cc/'
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
    console.log("Fetching Step 1: loadon page...");
    const res1 = await get(url1);
    console.log("Step 1 Status:", res1.status);
    const cookies = parseCookies(res1.headers['set-cookie']);
    console.log("Cookies:", cookies);

    console.log("\nFetching Step 2: redirectingfree page...");
    const res2 = await get(url2, {
        'Cookie': cookies,
        'Referer': 'https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC0lZDklODYlZDklODglZDklODYtJWQ4JWE3JWQ5JTg0JWQ5JTg2JWQ4JWIzJWQ5JTg4JWQ4JWE5LSVkOCVhNyVkOSU4NCVkOCVhNyVkOSU4OCVkOSU4NCVkOSU4YS93YXRjaGluZy8='
    });
    console.log("Step 2 Status:", res2.status);
    console.log("Step 2 Headers:", res2.headers);
    console.log("Step 2 Length:", res2.data.length);
    fs.writeFileSync('cimanow_redirectingfree.html', res2.data);
    console.log("Saved to cimanow_redirectingfree.html");
}

run();
