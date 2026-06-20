const fs = require('fs');

const html = fs.readFileSync('cimanow_watch.html', 'utf8');

let obfuscatedData = '';
const matches = html.matchAll(/['"](O(?:[A-Za-z0-9+/=]+~?)+)['"]/g);
for (const match of matches) {
    obfuscatedData += match[1];
}
obfuscatedData = obfuscatedData.replace(/[\r\n\t'+\s]/g, '');

const parts = obfuscatedData.split('~');
console.log("Parts count:", parts.length);

const _r = 87653;

let decrypted = '';
for (let i = 0; i < parts.length; i++) {
    if (!parts[i]) continue;
    try {
        const decoded = Buffer.from(parts[i], 'base64').toString('utf8');
        const num = parseInt(decoded.replace(/\D/g, '')) - _r;
        decrypted += String.fromCharCode(num);
    } catch(e) {}
}

console.log("Decrypted length:", decrypted.length);

function utf8Decode(str) {
    try { return decodeURIComponent(escape(str)); } catch(e) { return str; }
}

const finalHtml = utf8Decode(decrypted);
console.log("Final HTML length:", finalHtml.length);
fs.writeFileSync('cimanow_watch_decrypted_full.html', finalHtml);
console.log("Written to cimanow_watch_decrypted_full.html");
