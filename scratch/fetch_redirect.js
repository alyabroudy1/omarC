const https = require('https');

const url = 'https://cswru.vid872.top/v2/re.php?o=c2U1L0tUV3BhekptMFgxckRBWkxFTDBqVjZzZnluZlNBSDBvRTJRdGcvKy90QWtFNmoybkhuQXlEZXQ0TFd2Z1RYL01xK0pLR0N6bXdTMlo1ZGY3M2c9PQ==';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://cswru.vid872.top/'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log(`Headers:`, res.headers);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        console.log(`Body (first 500 chars):`, data.substring(0, 500));
    });
}).on('error', (err) => {
    console.error(err);
});
