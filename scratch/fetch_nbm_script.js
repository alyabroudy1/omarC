const https = require('https');
const fs = require('fs');

const url = 'https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/nbm2oHaQcN60y6Ozwm.js?v=2.3';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://cimanow.cc/'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        fs.writeFileSync('scratch/nbm_script.js', data);
        console.log(`Saved to scratch/nbm_script.js, size: ${data.length}`);
    });
}).on('error', (err) => {
    console.error(err);
});
