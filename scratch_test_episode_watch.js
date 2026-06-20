const https = require('https');
const fs = require('fs');
const jsdom = require('jsdom');

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
        const url = 'https://m.cimaleek.pw/episodes/the-penguin-11-973979/watch/';
        console.log(`Fetching episode watch page: ${url}`);
        const html = await fetchUrl(url);
        fs.writeFileSync('temp_episode_watch.html', html);
        
        const dom = new jsdom.JSDOM(html);
        const doc = dom.window.document;
        
        const serverItems = doc.querySelectorAll('.lalaplay_player_option');
        console.log(`Found ${serverItems.length} server elements on episode watch page`);
        for (let el of serverItems) {
            console.log(`ID: ${el.id} | data-post: ${el.getAttribute('data-post')} | data-nume: ${el.getAttribute('data-nume')} | data-type: ${el.getAttribute('data-type')} | Text: ${el.textContent.trim()}`);
        }
    } catch (e) {
        console.error("Error:", e);
    }
}
run();
