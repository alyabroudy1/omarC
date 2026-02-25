const https = require('https');

// Simulate the exact extraction flow of SavefilesExtractor.kt
const url = 'https://savefiles.com/e/fepgxkbktt8t';
const fileCode = 'fepgxkbktt8t';

const headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': url,
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Content-Type': 'application/x-www-form-urlencoded'
};

const postData = `file_code=${fileCode}&referer=https%3A%2F%2Fsavefiles.com%2F`;

console.log('Fetching Savefiles directly via POST /dl (simulating interstitial bypass)...');

const req = https.request('https://savefiles.com/dl', {
    method: 'POST',
    headers: headers
}, (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
        console.log(`Response Code: ${res.statusCode}`);

        if (res.statusCode === 403) {
            console.log('Blocked by Cloudflare (403).');
            return;
        }

        // 1. Direct Regex (sources: [{file: "..."}])
        let match = data.match(/sources:\s*\[\s*(?:\{\s*file:\s*)?["']([^"']+)["']/);
        let videoUrl = match ? match[1] : null;

        if (!videoUrl) {
            console.log("Direct regex failed. Looking for packed JS...");
            let p1 = data.split('eval(function(p,a,c,k,e,d)');
            if (p1.length > 1) {
                let packed = p1[1].split('</script>')[0];
                console.log("Packed JS found! Length: " + packed.length);
                // Simple output for now.
                console.log("Packed JS Start: " + packed.substring(0, 100));
            } else {
                console.log("No packed JS found.");
                const fs = require('fs');
                fs.writeFileSync('savefiles_debug.html', data);
                console.log("Saved response to savefiles_debug.html");
            }
        }

        console.log(`Extracted raw videoUrl: ${videoUrl}`);

        // 2. Base64 fallback logic exactly as implemented in SavefilesExtractor.kt
        if (videoUrl && !videoUrl.startsWith('http') && !videoUrl.startsWith('//')) {
            console.log('URL is not HTTP. Attempting Base64 decode...');
            try {
                let decoded = Buffer.from(videoUrl, 'base64').toString('utf8');
                if (decoded.startsWith('http')) {
                    videoUrl = decoded;
                    console.log('SUCCESS: Decoded Base64 video URL -> ' + videoUrl);
                }
            } catch (e) {
                console.log('Base64 decode failed');
            }
        }

        console.log(`\nFinal playable URL passed to callback: ${videoUrl}`);
    });
});

req.on('error', e => console.error(e));
req.write(postData);
req.end();
