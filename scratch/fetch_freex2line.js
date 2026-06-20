const https = require('https');
const fs = require('fs');

const url = 'https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC0lZDklODYlZDklODglZDklODYtJWQ4JWE3JWQ5JTg0JWQ5JTg2JWQ4JWIzJWQ5JTg4JWQ4JWE5LSVkOCVhNyVkOSU4NCVkOCVhNyVkOSU4OCVkOSU4NCVkOSU4YS93YXRjaGluZy8=';

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

async function run() {
    console.log("Fetching freex2line page...");
    const res = await get(url);
    console.log("Status:", res.status);
    console.log("Headers:", res.headers);
    console.log("Length:", res.data.length);
    fs.writeFileSync('cimanow_freex2line.html', res.data);
    console.log("Saved to cimanow_freex2line.html");
}

run();
