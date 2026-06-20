const https = require('https');
const Buffer = require('buffer').Buffer;

function fetchUrl(url) {
    return new Promise((resolve, reject) => {
        https.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Referer': 'https://cimanow.cc/'
            }
        }, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => { resolve(data); });
        }).on('error', reject);
    });
}

async function run() {
    // A random movie on cimanow
    const movieUrl = 'https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-the-fall-guy-2024-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/';
    console.log("Fetching: " + movieUrl);
    const detailHtml = await fetchUrl(movieUrl);
    
    const watchMatch = detailHtml.match(/href=["'](https?:\/\/[^"']*freex2line[^"']*)["']/i);
    if (!watchMatch) {
        console.log("No freex2line link found");
        return;
    }
    
    const wrapperUrl = watchMatch[1];
    console.log("Wrapper URL: " + wrapperUrl);
    const base64Url = new URL(wrapperUrl).searchParams.get('link');
    const watchUrl = Buffer.from(base64Url, 'base64').toString('utf8');
    console.log("Watch URL: " + watchUrl);
    
    const watchHtml = await fetchUrl(watchUrl);
    console.log("Watch HTML length: " + watchHtml.length);
    
    // Decrypt watch page
    const cVarMatch = watchHtml.match(/var _c\d+\s*=\s*'([\s\S]*?)';/);
    if (!cVarMatch) {
        console.log("No obfuscated _c variable found");
        return;
    }
    const obfuscatedData = cVarMatch[1].replace(/[\r\n\t'+\s]/g, '');
    const rMatch = watchHtml.match(/var _r\s*=\s*(\d+)\s*\+\s*(\d+)\s*\+\s*(\d+);/);
    const _r = parseInt(rMatch[1]) + parseInt(rMatch[2]) + parseInt(rMatch[3]);
    
    let decrypted = '';
    const parts = obfuscatedData.split('~');
    for (let i = 0; i < parts.length; i++) {
        if (!parts[i]) continue;
        try {
            const decoded = Buffer.from(parts[i], 'base64').toString('utf8');
            const num = parseInt(decoded.replace(/\D/g, '')) - _r;
            decrypted += String.fromCharCode(num);
        } catch(e) {}
    }
    
    function utf8Decode(str) {
        try { return decodeURIComponent(escape(str)); } catch(e) { return str; }
    }
    const finalHtml = utf8Decode(decrypted);
    
    const fs = require('fs');
    fs.writeFileSync('cimanow_test_watch_multi.html', finalHtml);
    console.log("Decrypted HTML written to cimanow_test_watch_multi.html");
    
    // Check for iframes
    const iframes = finalHtml.match(/<iframe[^>]*>/g);
    console.log("Iframes: ", iframes);
    
    // Check for buttons
    const btns = finalHtml.match(/<ul class="btns">([\s\S]*?)<\/ul>/);
    if (btns) {
        console.log("Buttons HTML:\n" + btns[1]);
    } else {
        console.log("No ul.btns found");
    }
}

run();
