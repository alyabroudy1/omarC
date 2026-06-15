const https = require('https');
const fs = require('fs');

const url = 'https://cswru.vid872.top/e4/UG5yUXFjZDZTK1pQZXZIeVl0TjRxOUlOSUkwZHNyWFZpRVhOZFVNWjZKUXFhaWpXOUZvU1AwanM2THZLT01nMUk5ejkzRysrUnQwYjU3THVpTlplekZCRFdvYTRBOUN3UU5QN0t3NnZaQ0ZXSW1wV0JLcjZId0FjN0oyNmVpS1orSjNuMXhVS1hmeFJ6SWU0K0Nsa1ZSRzEyVld3YVBTd00zMmVDbnh5Umx3QzVIczd6S3p1bVlSVnp0QzhBc0pHS1lhWHVzWDNLOEowQUVVUDZsRnVPOXhudG42TzNTdW5MV21BbTFwd2ROUVdQNmJGU2FrN1NPTHVRNmtmOC9MWmd6ejBZWGZhNVJlSE93NzkxcDJQUnV1RElzc0FBTFoyWHd5U2NiRTI2d3Y2WUxqUFo1MUZCam1NeGpGa3A0UFd5TW9zUTBXcHpSaStPeXRxdUxQMUJ3PT0.html';

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
        fs.writeFileSync('server8.html', data);
        console.log(`Saved to server8.html. Size: ${data.length}`);
    });
}).on('error', (err) => {
    console.error(err);
});
