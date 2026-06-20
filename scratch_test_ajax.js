const https = require('https');
const querystring = require('querystring');

function makeRequest(method, url, headers = {}, body = null) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: method,
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Referer': 'https://m.cimaleek.pw/movies/venom-633705/watch/',
                ...headers
            }
        };
        
        const req = https.request(options, (res) => {
            console.log(`[${method}] ${url} -> Status: ${res.statusCode}`);
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => { resolve({ status: res.statusCode, headers: res.headers, data: data }); });
        });
        
        req.on('error', reject);
        if (body) {
            req.write(body);
        }
        req.end();
    });
}

async function run() {
    // Let's use the parameters from the Venom movie watch page:
    // data-post: 4354, data-nume: 1, data-type: Cloud_V3 (or movie)
    const post = '4354';
    const nume = '1';
    const type = 'Cloud_V3';

    console.log("=== Testing GET/POST endpoints ===");

    // Test 1: POST to /wp-json/lalaplayer/v2/ with JSON body
    try {
        const jsonBody = JSON.stringify({ post: post, nume: nume, type: type });
        const res = await makeRequest('POST', 'https://m.cimaleek.pw/wp-json/lalaplayer/v2/', {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        }, jsonBody);
        console.log("Response data (first 300 chars):", res.data.substring(0, 300));
    } catch(e) { console.error("Test 1 error:", e); }

    // Test 2: POST to /wp-json/lalaplayer/v2/ with Form Data
    try {
        const formBody = querystring.stringify({ post: post, nume: nume, type: type });
        const res = await makeRequest('POST', 'https://m.cimaleek.pw/wp-json/lalaplayer/v2/', {
            'Content-Type': 'application/x-www-form-urlencoded',
            'X-Requested-With': 'XMLHttpRequest'
        }, formBody);
        console.log("Response data (first 300 chars):", res.data.substring(0, 300));
    } catch(e) { console.error("Test 2 error:", e); }

    // Test 3: POST to /wp-admin/admin-ajax.php with Form Data
    try {
        // Typically the action is 'lalaplay_player_option' or 'get_player_option' or 'action_player'
        const actions = ['lalaplay_player_option', 'player_option', 'get_player', 'lalaplayer'];
        for (let action of actions) {
            const formBody = querystring.stringify({ action: action, post: post, nume: nume, type: type });
            const res = await makeRequest('POST', 'https://m.cimaleek.pw/wp-admin/admin-ajax.php', {
                'Content-Type': 'application/x-www-form-urlencoded',
                'X-Requested-With': 'XMLHttpRequest'
            }, formBody);
            console.log(`Action: ${action} -> Response (first 100 chars):`, res.data.substring(0, 100));
        }
    } catch(e) { console.error("Test 3 error:", e); }

    // Test 4: GET to /wp-json/lalaplayer/v2/?post=4354&nume=1&type=Cloud_V3
    try {
        const res = await makeRequest('GET', `https://m.cimaleek.pw/wp-json/lalaplayer/v2/?post=${post}&nume=${nume}&type=${type}`);
        console.log("Response data (first 300 chars):", res.data.substring(0, 300));
    } catch(e) { console.error("Test 4 error:", e); }

    // Test 5: GET to /wp-json/lalaplayer/v2/post/type/nume
    try {
        const res = await makeRequest('GET', `https://m.cimaleek.pw/wp-json/lalaplayer/v2/${post}/${type}/${nume}`);
        console.log("Response data (first 300 chars):", res.data.substring(0, 300));
    } catch(e) { console.error("Test 5 error:", e); }

    // Test 6: GET to /wp-json/lalaplayer/v2/post/nume
    try {
        const res = await makeRequest('GET', `https://m.cimaleek.pw/wp-json/lalaplayer/v2/${post}/${nume}`);
        console.log("Response data (first 300 chars):", res.data.substring(0, 300));
    } catch(e) { console.error("Test 6 error:", e); }
}

run();
