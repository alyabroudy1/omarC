const https = require('https');

const EMBED_URL = 'https://vidoba.org/embed-51kirhtklgfy.html';

function fetchUrl(url, headers = {}) {
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

async function run() {
    console.log("Fetching embed page...");
    const embedRes = await fetchUrl(EMBED_URL, {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://larozza.casa/'
    });
    
    console.log("Embed Page Status:", embedRes.status);
    console.log("Embed Page Headers:", JSON.stringify(embedRes.headers, null, 2));
}

run();
