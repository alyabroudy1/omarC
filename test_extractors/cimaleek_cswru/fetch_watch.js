const https = require('https');
const fs = require('fs');

const url = 'https://m.cimaleek.pw/movies/venom-the-last-dance-889877/watch/';

function fetchPage(targetUrl) {
    console.log(`Fetching: ${targetUrl}`);
    const options = {
        headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://m.cimaleek.pw/movies/venom-the-last-dance-889877/',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Connection': 'keep-alive'
        }
    };

    https.get(targetUrl, options, (res) => {
        console.log(`Status: ${res.statusCode}`);
        console.log(`Headers:`, res.headers);

        let data = '';
        res.on('data', (chunk) => { data += chunk; });
        res.on('end', () => {
            fs.writeFileSync('temp_watch_test.html', data);
            console.log(`Saved to temp_watch_test.html. Length: ${data.length}`);
            if (data.includes('location.href') || data.includes('window.location') || data.includes('replace(')) {
                console.log('Detected potential JS redirects in HTML!');
            }
        });
    }).on('error', (err) => {
        console.error('Error:', err);
    });
}

fetchPage(url);
