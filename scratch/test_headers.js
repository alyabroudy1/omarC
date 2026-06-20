const https = require('https');

const url = 'https://m.cimaleek.pw/movies/venom-the-last-dance-889877/watch/';

function testFetch(headers, label) {
    console.log(`\n--- Testing: ${label} ---`);
    const parsed = new URL(url);
    const options = {
        hostname: parsed.hostname,
        path: parsed.pathname,
        headers: headers
    };

    https.get(options, (res) => {
        console.log(`Status: ${res.statusCode}`);
        console.log(`Location: ${res.headers.location || 'none'}`);
    }).on('error', (err) => {
        console.error('Error:', err);
    });
}

// 1. Empty headers (like standard Java/OkHttp defaults)
testFetch({}, 'No headers');

// 2. Only user-agent (standard CloudStream style)
testFetch({
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
}, 'Only User-Agent');

// 3. User-Agent + Referer
testFetch({
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
    'Referer': 'https://m.cimaleek.pw/movies/venom-the-last-dance-889877/'
}, 'User-Agent + Referer');
