const fs = require('fs');

const html = fs.readFileSync('cimanow_watch_302.html', 'utf8');

// 1. Extract _c9896 string
const cVarMatch = html.match(/var _c9896\s*=\s*'([\s\S]*?)';/);
if (!cVarMatch) {
    console.error("Failed to find _c9896!");
    process.exit(1);
}
const _c9896 = cVarMatch[1].replace(/[\r\n\t'+\s]/g, ''); // clean up formatting

// 2. Extract _r values
const rMatch = html.match(/var _r\s*=\s*(\d+)\s*\+\s*(\d+)\s*\+\s*(\d+);/);
if (!rMatch) {
    console.error("Failed to find _r formula!");
    process.exit(1);
}
const _r = parseInt(rMatch[1]) + parseInt(rMatch[2]) + parseInt(rMatch[3]);
console.log(`Formula: ${rMatch[1]} + ${rMatch[2]} + ${rMatch[3]} = ${_r}`);

// 3. Decrypt
console.log("Decrypting...");
let _ecc89 = '';
const parts = _c9896.split('~');
console.log(`Total parts: ${parts.length}`);

for (let i = 0; i < parts.length; i++) {
    if (!parts[i]) continue;
    try {
        const decoded = Buffer.from(parts[i], 'base64').toString('utf8');
        const num = parseInt(decoded.replace(/\D/g, '')) - _r;
        _ecc89 += String.fromCharCode(num);
    } catch(e) {
        console.error(`Failed at index ${i}: ${e.message}`);
    }
}

// Escape/unescape string (standard legacy JS approach used for UTF-8 bytes)
function utf8Decode(str) {
    try {
        return decodeURIComponent(escape(str));
    } catch(e) {
        // Fallback if escape/unescape fails
        return str;
    }
}

const finalHtml = utf8Decode(_ecc89);
fs.writeFileSync('cimanow_watch_decrypted.html', finalHtml);
console.log("Decrypted HTML saved to cimanow_watch_decrypted.html");
console.log(`Length: ${finalHtml.length}`);
console.log(`Preview: \n${finalHtml.substring(0, 500)}`);
