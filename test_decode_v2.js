const fs = require('fs');

const html = fs.readFileSync('temp_watch_guy.html', 'utf8');

// The new format:
// var _0819c=''; var _dk1=83146;var _dk2=74091; var _169c2= 'ODAxMDg~...';
// Match the entire multiline concatenated string: var _[varname] = '...' + '...' ...;
const obfuscatedMatch = html.match(/var\s+_\w+\s*=\s*([\s\S]*?);/);
if (!obfuscatedMatch) {
    console.log("Not found");
    return;
}
// Filter to make sure it's the large base64 data (should contain many '~' characters)
let obfuscatedData = '';
const matches = html.matchAll(/['"](O(?:[A-Za-z0-9+/=]+~?)+)['"]/g);
for (const match of matches) {
    obfuscatedData += match[1];
}
obfuscatedData = obfuscatedData.replace(/[\r\n\t'+\s]/g, '');

const formulaMatch = html.match(/var\s+_r\s*=\s*([\d\s+\-*\/()]+);/);
if (!formulaMatch) {
    console.log("_r variable formula not found");
    return;
}

const _r = eval(formulaMatch[1]);
console.log(`Formula: ${formulaMatch[1]} = ${_r}`);

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

console.log("finalHtml length:", finalHtml.length);
console.log("first 500 characters:", JSON.stringify(finalHtml.substring(0, 500)));

fs.writeFileSync('temp_guy_decrypted_v2.html', finalHtml);
console.log("Decrypted HTML written to temp_guy_decrypted_v2.html");

const iframes = finalHtml.match(/<iframe[^>]*>/g);
console.log("Iframes: ", iframes);

const btns = finalHtml.match(/<ul class=["']btns["']>([\s\S]*?)<\/ul>/i);
if (btns) {
    console.log("Buttons HTML:\n" + btns[1]);
} else {
    console.log("No ul.btns found");
}
