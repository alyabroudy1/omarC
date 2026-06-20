const https = require('https');
const fs = require('fs');

const url = 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log(`Headers:`, res.headers);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        fs.writeFileSync('cimanow_detail.html', data);
        console.log(`Saved to cimanow_detail.html. Size: ${data.length}`);
        
        // Find if there is a watching/ or watch/ link
        const regex = /href="([^"]+)"/g;
        let match;
        const links = [];
        while ((match = regex.exec(data)) !== null) {
            const link = match[1];
            if (link.includes('watching') || link.includes('watch') || link.includes('play')) {
                links.push(link);
            }
        }
        console.log("Found watch/watching/play links:", links);
    });
}).on('error', (err) => {
    console.error(err);
});
