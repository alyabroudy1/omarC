const https = require('https');
const fs = require('fs');

const url = 'https://cswru.vid872.top/e2/MkJCV1QzVWRCU0V5dlFWOWFlLzIydGJ0bEZublJ5UlpRSkRaaDV2ZDRwNkZIYTZQcFNwTUQ4ck9BL05HZFNFaW1ZeWdHdjgzRzloR0haTHVPWUdtL3E5c3FVV21qQzZlZVB3Q1FvS2JNbXg5VnRsNk1sTU1pRXlqRXBabGd2MFdGNkxaSXhWMkh1bGdnZ0EvajcxTEd0V2tJaUhsK0FxMXZ2OWY1SW5ieUZWcjBSU0IyT3Qybnkya0Y5U1pES0N3QXZqQzYwVzBWYXV4TzE5UVBhZENPcTN0dXpIYzBVVU9lN0RtV2hyZk9naHBsbkNmaVV3NDNyandWc2VSR0hSWUl1SlRweXYrV1VCWmwxWDVTNFA4TlVhOGxuLzZuRU1PNS9iSHRQZGVCYzVML0UyekU0WXdNbDhNN3hIeTRwUTE.html';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://m.cimaleek.pw/movies/venom-the-last-dance-889877/watch/'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        fs.writeFileSync('server3.html', data);
        console.log(`Saved to server3.html. Size: ${data.length}`);
    });
}).on('error', (err) => {
    console.error(err);
});
