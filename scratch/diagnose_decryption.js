const fs = require('fs');
const html = fs.readFileSync('cimanow_watch.html', 'utf8');

// Match all single/double quoted base64 strings starting with O
let obfuscatedData = '';
const matches = html.matchAll(/['"](O(?:[A-Za-z0-9+/=]+~?)+)['"]/g);
let count = 0;
for (const match of matches) {
    obfuscatedData += match[1];
    count++;
}
obfuscatedData = obfuscatedData.replace(/[\r\n\t'+\s]/g, '');

console.log("Found matches count:", count);
console.log("obfuscatedData total length:", obfuscatedData.length);

const parts = obfuscatedData.split('~');
console.log("parts count:", parts.length);

const formulaMatch = html.match(/var\s+_r\s*=\s*([\d\s+\-*\/()]+);/);
if (!formulaMatch) {
    console.log("No _r formula found!");
    return;
}
const _r = eval(formulaMatch[1]);
console.log("Formula parsed:", formulaMatch[1], "value:", _r);

let decrypted = '';
for (let i = 0; i < parts.length; i++) {
    if (!parts[i]) continue;
    try {
        const decoded = Buffer.from(parts[i], 'base64').toString('utf8');
        const num = parseInt(decoded.replace(/\D/g, '')) - _r;
        decrypted += String.fromCharCode(num);
    } catch(e) {}
}

console.log("decrypted length:", decrypted.length);
fs.writeFileSync('temp_decrypted_full.html', decrypted);
