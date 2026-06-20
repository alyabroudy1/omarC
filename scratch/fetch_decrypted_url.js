const https = require('https');
const fs = require('fs');

const url = 'https://miixdrop.net/e/z1xvw6l4tnj1zw';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://m.cimaleek.pw/'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log(`Headers:`, res.headers);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        fs.writeFileSync('temp_decrypted_page.html', data);
        console.log(`Saved to temp_decrypted_page.html, size: ${data.length}`);
    });
}).on('error', (err) => {
    console.error(err);
});
