const https = require('https');
const fs = require('fs');

function fetchUrl(url) {
    return new Promise((resolve, reject) => {
        https.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        }, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => { resolve(data); });
        }).on('error', reject);
    });
}

async function run() {
    try {
        const url = 'https://m.cimaleek.pw/wp-content/themes/cimalek_v8.6/assets/js/front.scripts.min.js';
        console.log("Fetching front.scripts.min.js...");
        const content = await fetchUrl(url);
        fs.writeFileSync('temp_front_scripts.js', content, 'utf8');
        console.log("Downloaded successfully! Size:", content.length);
        
        console.log("Searching for 'IOns'...");
        let idx = content.indexOf('IOns');
        if (idx !== -1) {
            console.log("Found 'IOns' at index:", idx);
            console.log("Context:", content.substring(idx - 100, idx + 300));
        } else {
            console.log("'IOns' not found");
        }

        console.log("Searching for 'MdTq'...");
        let idx2 = content.indexOf('MdTq');
        if (idx2 !== -1) {
            console.log("Found 'MdTq' at index:", idx2);
            console.log("Context:", content.substring(idx2 - 100, idx2 + 300));
        } else {
            console.log("'MdTq' not found");
        }
    } catch(e) {
        console.error(e);
    }
}
run();
