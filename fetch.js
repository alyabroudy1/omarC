const https = require('https');
const fs = require('fs');

const url = 'https://qq.okprime.site/embed-rk3dfxfjyrb8.html';
const options = {
    headers: {
        'Referer': 'https://laroza.cfd/',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
};

https.get(url, options, (res) => {
    let data = '';
    res.on('data', (chunk) => {
        data += chunk;
    });
    res.on('end', () => {
        fs.writeFileSync('embed_clean.html', data, 'utf8');
        console.log('Downloaded ' + data.length + ' bytes');
    });
}).on('error', (e) => {
    console.error(e);
});
