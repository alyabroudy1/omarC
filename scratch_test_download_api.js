const https = require('https');

function postDownloadApi(postId, rand) {
    const postData = `p=${postId}&rand=${rand}`;
    const options = {
        hostname: 'cimanow.cc',
        port: 443,
        path: '/wp-json/direct_download/v1/',
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'Content-Length': Buffer.byteLength(postData),
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36',
            'Referer': 'https://cimanow.cc/'
        }
    };

    const req = https.request(options, (res) => {
        let data = '';
        res.on('data', (chunk) => {
            data += chunk;
        });
        res.on('end', () => {
            console.log('Status Code:', res.statusCode);
            console.log('Headers:', res.headers);
            console.log('Response:', data);
        });
    });

    req.on('error', (e) => {
        console.error('Request Error:', e);
    });

    req.write(postData);
    req.end();
}

// Post ID from cimanow_episode.html
postDownloadApi('128476', '9b09102b216d23cbb6cf75b47c82961c');
