const https = require('https');
const fs = require('fs');

const url = 'https://rm.freex2line.online/1c241c9f4c.js';

const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': 'https://rm.freex2line.online/2020/02/blog-post.html/'
};

function get(url) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: 'GET',
            headers: headers
        };
        https.get(options, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                resolve({ status: res.statusCode, data: data });
            });
        }).on('error', reject);
    });
}

async function run() {
    const res = await get(url);
    console.log("Status:", res.status);
    console.log("Length:", res.data.length);
    fs.writeFileSync('1c241c9f4c.js', res.data);
    console.log("Saved to 1c241c9f4c.js");
}

run();
