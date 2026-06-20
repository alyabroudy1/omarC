const https = require('https');

const url = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/watching/';
const refererUpper = 'https://cimanow.cc/%D9%85%D8%B3%D9%84%D8%B3%D9%84-%D9%86%D9%88%D9%86-%D8%A7%D9%84%D9%86%D8%B3%D9%88%D8%A9-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-1-%D8%A7%D9%84%D8%A7%D9%88%D9%84%D9%8a/';
const refererLower = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/';

function get(ref) {
    return new Promise((resolve) => {
        const parsedUrl = new URL(url);
        https.get({
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname,
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Referer': ref
            }
        }, (res) => {
            resolve({ status: res.statusCode, location: res.headers.location });
        }).on('error', (e) => resolve({ error: e.message }));
    });
}

async function run() {
    console.log("Testing with Uppercase Referer...");
    const res1 = await get(refererUpper);
    console.log("Result:", res1);
    console.log("--------------------------------");

    console.log("Testing with Lowercase Referer...");
    const res2 = await get(refererLower);
    console.log("Result:", res2);
    console.log("--------------------------------");
}

run();
