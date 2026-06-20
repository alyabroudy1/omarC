const fs = require('fs');

const html = fs.readFileSync('cimanow_watch_noon.html', 'utf8');

// 1. Extract all obfuscated chunks in the entire file
let obfuscatedData = '';
const matches = html.matchAll(/['"](O(?:[A-Za-z0-9+/=]+~?)+)['"]/g);
let chunkCount = 0;
for (const match of matches) {
    obfuscatedData += match[1];
    chunkCount++;
}
obfuscatedData = obfuscatedData.replace(/[\r\n\t'+\s]/g, '');
console.log(`Found ${chunkCount} chunks globally. Obfuscated data length: ${obfuscatedData.length}`);

// 2. Resolve _r value
let rValue = null;
const rMatch = /var\s+_r\s*=\s*([\d\s+\-*\/()]+);/.exec(html);
if (rMatch) {
    rValue = eval(rMatch[1]);
} else {
    const literalMatch = /-\s*(\d+)\s*\)\s*\}\s*\)\s*;/.exec(html)
        || /-\s*(\d+)\s*\)\s*;\s*\}\s*\)\s*;\s*document\.open/.exec(html)
        || /-\s*(\d+)\s*\)\s*;/.exec(html);
    if (literalMatch) {
        rValue = parseInt(literalMatch[1], 10);
    }
}
console.log(`Resolved _r: ${rValue}`);

if (rValue === null) {
    console.error("Could not resolve _r");
    process.exit(1);
}

// 3. Decrypt tokens
const parts = obfuscatedData.split('~');
console.log("Parts count:", parts.length);

let decrypted = '';
for (let i = 0; i < parts.length; i++) {
    if (!parts[i]) continue;
    try {
        let padded = parts[i];
        while (padded.length % 4 !== 0) {
            padded += '=';
        }
        const decoded = Buffer.from(padded, 'base64').toString('utf8');
        const num = parseInt(decoded.replace(/\D/g, ''), 10) - rValue;
        decrypted += String.fromCharCode(num);
    } catch(e) {}
}

console.log("Decrypted length:", decrypted.length);

function utf8Decode(str) {
    try { return decodeURIComponent(escape(str)); } catch(e) { return str; }
}

const finalHtml = utf8Decode(decrypted);
console.log("Final HTML length:", finalHtml.length);
fs.writeFileSync('cimanow_watch_noon_decrypted_full.html', finalHtml);
console.log("Written to cimanow_watch_noon_decrypted_full.html");

// Check for download links
const dlRegex = /<a[^>]+href=["'](https?:\/\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
let dlMatch;
console.log("\nSearching for download links in full decrypted html:");
while ((dlMatch = dlRegex.exec(finalHtml)) !== null) {
    const url = dlMatch[1];
    const text = dlMatch[2].replace(/<[^>]+>/g, '').trim();
    if (url.includes('download') || text.includes('تحميل') || text.includes('Quality') || text.match(/\d{3,4}p/)) {
        console.log(`Link: ${text} -> ${url}`);
    }
}
