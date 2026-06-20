const https = require('https');

const url = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/watching/';
const referer = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/';

const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': referer,
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7',
    'Accept-Language': 'en-US,en;q=0.9',
    'Sec-Ch-Ua': '"Not_A Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"',
    'Sec-Ch-Ua-Mobile': '?0',
    'Sec-Ch-Ua-Platform': '"Windows"',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'same-origin',
    'Sec-Fetch-User': '?1',
    'Upgrade-Insecure-Requests': '1'
};

function get() {
    return new Promise((resolve) => {
        const parsedUrl = new URL(url);
        https.get({
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname,
            headers: headers
        }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                resolve({ status: res.statusCode, location: res.headers.location, length: data.length });
            });
        }).on('error', (e) => resolve({ error: e.message }));
    });
}

async function run() {
    console.log("Testing with Chrome suite headers...");
    const result = await get();
    console.log("Result:", { status: result.status, location: result.location, length: result.length });
    
    // Write body to file
    const fs = require('fs');
    const JSDOM = require('jsdom').JSDOM;
    
    const parsedUrl = new URL(url);
    https.get({
        hostname: parsedUrl.hostname,
        path: parsedUrl.pathname,
        headers: headers
    }, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
            fs.writeFileSync('cimanow_watch_302.html', data);
            console.log("Saved to cimanow_watch_302.html");
            
            // Check for server elements in DOM
            const dom = new JSDOM(data);
            const doc = dom.window.document;
            const servers = doc.querySelectorAll('ul.tabcontent li');
            console.log(`Found ${servers.length} server elements in 302 response body!`);
            servers.forEach((s, i) => {
                console.log(`  [Server ${i}]: name="${s.textContent.trim()}" index="${s.getAttribute('data-index')}" id="${s.getAttribute('data-id')}"`);
            });
        });
    });
}

run();
