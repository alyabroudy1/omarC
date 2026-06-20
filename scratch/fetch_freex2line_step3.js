const https = require('https');
const fs = require('fs');

const url1 = 'https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC0lZDklODYlZDklODglZDklODYtJWQ4JWE3JWQ5JTg0JWQ5JTg2JWQ4JWIzJWQ5JTg4JWQ4JWE5LSVkOCVhNyVkOSU4NCVkOCVhNyVkOSU4OCVkOSU4NCVkOSU4YS93YXRjaGluZy8=';
const url2 = 'https://rm.freex2line.online/redirectingfree/';
const url3 = 'https://rm.freex2line.online/2020/02/blog-post.html';

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
    const cookies = parseCookies(res1.headers['set-cookie']);

    console.log("Fetching Step 2: redirectingfree page...");
    await get(url2, { 'Cookie': cookies, 'Referer': url1 });

    console.log("Fetching Step 3: blog-post page...");
    const res3 = await get(url3, {
        'Cookie': cookies,
        'Referer': 'https://href.li/'
    });
    console.log("Step 3 Status:", res3.status);
    console.log("Step 3 Headers:", res3.headers);
    console.log("Step 3 Length:", res3.data.length);
    fs.writeFileSync('cimanow_blog_post.html', res3.data);
    console.log("Saved to cimanow_blog_post.html");
}

run();
