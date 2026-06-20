const fs = require('fs');

const code = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

// Extract all string literals
const stringRegex = /"([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)'/g;
let match;
const strings = new Set();
while ((match = stringRegex.exec(code)) !== null) {
    if (match[1]) strings.add(match[1]);
    if (match[2]) strings.add(match[2]);
}

const md5Regex = /^[a-f0-9]{32}$/;

for (const str of strings) {
    if (str.length !== 32) continue;
    
    for (let key = 1; key < 255; key++) {
        let xored = '';
        for (let i = 0; i < str.length; i++) {
            xored += String.fromCharCode(str.charCodeAt(i) ^ key);
        }
        if (md5Regex.test(xored)) {
            console.log(`Found rand token! Key: ${key}, Token: ${xored}, Original: ${str}`);
        }
    }
}
