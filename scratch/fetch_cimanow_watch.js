const https = require('https');
const fs = require('fs');

const url = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/watching/';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://cimanow.cc/'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log(`Headers:`, res.headers);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        fs.writeFileSync('cimanow_watch_noon.html', data);
        console.log(`Saved to cimanow_watch_noon.html, size: ${data.length}`);
    });
}).on('error', (err) => {
    console.error(err);
});
