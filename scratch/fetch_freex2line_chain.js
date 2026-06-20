const https = require('https');
const fs = require('fs');

const initialUrl = 'https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC0lZDklODYlZDklODglZDklODYtJWQ4JWE3JWQ5JTg0JWQ5JTg2JWQ4JWIzJWQ5JTg4JWQ4JWE5LSVkOCVhNyVkOSU4NCVkOCVhNyVkOSU4OCVkOSU4NCVkOSU4YS93YXRjaGluZy8=';

let cookies = {};

function getCookiesString() {
    return Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ');
}

function updateCookies(cookieHeaders) {
    if (!cookieHeaders) return;
    cookieHeaders.forEach(cookieStr => {
        const parts = cookieStr.split(';')[0].split('=');
        if (parts.length >= 2) {
            cookies[parts[0].trim()] = parts[1].trim();
        }
    });
}

function request(url, method = 'GET', postData = null, referer = 'https://cimanow.cc/') {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': referer
        };
        const cookieStr = getCookiesString();
        if (cookieStr) {
            headers['Cookie'] = cookieStr;
        }

        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: method,
            headers: headers
        };

        const req = https.request(options, (res) => {
            updateCookies(res.headers['set-cookie']);
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                resolve({ status: res.statusCode, headers: res.headers, data: data });
            });
        });

        req.on('error', reject);
        if (postData) {
            req.write(postData);
        }
        req.end();
    });
}

async function fetchWithRedirects(url, referer = 'https://cimanow.cc/') {
    let currentUrl = url;
    let currentReferer = referer;
    let hops = 0;
    while (hops < 10) {
        console.log(`[Hop ${hops}] Requesting: ${currentUrl}`);
        const res = await request(currentUrl, 'GET', null, currentReferer);
        console.log(`  Status: ${res.status}`);
        if (res.headers.location) {
            let nextUrl = res.headers.location;
            if (!nextUrl.startsWith('http')) {
                const parsed = new URL(currentUrl);
                nextUrl = parsed.protocol + '//' + parsed.host + nextUrl;
            }
            currentReferer = currentUrl;
            currentUrl = nextUrl;
            hops++;
        } else {
            // Check for JS redirect
            const match = res.data.match(/window\.location\.href\s*=\s*['"]([^'"]+)['"]/);
            if (match) {
                let nextUrl = match[1];
                if (nextUrl.startsWith('https://href.li/?')) {
                    nextUrl = nextUrl.substring('https://href.li/?'.length);
                }
                if (!nextUrl.startsWith('http')) {
                    const parsed = new URL(currentUrl);
                    nextUrl = parsed.protocol + '//' + parsed.host + nextUrl;
                }
                currentReferer = currentUrl;
                currentUrl = nextUrl;
                hops++;
                console.log(`  JS Redirect to: ${currentUrl}`);
            } else {
                return res;
            }
        }
    }
    throw new Error("Too many redirects");
}

async function run() {
    try {
        const finalRes = await fetchWithRedirects(initialUrl);
        console.log("\nFinal Status:", finalRes.status);
        console.log("Final Length:", finalRes.data.length);
        console.log("Cookies accumulated:", JSON.stringify(cookies));
        fs.writeFileSync('cimanow_final_chain.html', finalRes.data);
        console.log("Saved to cimanow_final_chain.html");
    } catch (e) {
        console.error("Error in chain:", e);
    }
}

run();
