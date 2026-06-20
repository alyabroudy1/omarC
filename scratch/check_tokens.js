const fs = require('fs');

const html = fs.readFileSync('cimanow_watch_noon.html', 'utf8');

const splitMatch = /var\s+_parts\s*=\s*(\w+)\.split/.exec(html);
const varName = splitMatch[1];
const varRegex = new RegExp(`var\\s+${varName}\\s*=\\s*([\\s\\S]*?);`);
const assignmentContent = varRegex.exec(html)[1];

const chunkRegex = /['"]([A-Za-z0-9+/=~]+)['"]/g;
let chunks = '';
let match;
while ((match = chunkRegex.exec(assignmentContent)) !== null) {
    chunks += match[1];
}
const obfuscatedData = chunks.replace(/[\r\n\t'+\s]/g, '');

const rMatch = /var\s+_r\s*=\s*([\d\s+\-*\/()]+);/.exec(html);
const rValue = eval(rMatch[1]);
console.log("Resolved _r:", rValue);

const tokens = obfuscatedData.split('~');
console.log("Total tokens:", tokens.length);

let failCount = 0;
let successCount = 0;

console.log("\nFirst 30 tokens decryption details:");
for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (!token) continue;
    try {
        let padded = token;
        while (padded.length % 4 !== 0) {
            padded += '=';
        }
        const decodedStr = Buffer.from(padded, 'base64').toString('utf8');
        const digitsOnly = decodedStr.replace(/\D/g, '');
        
        if (!digitsOnly) {
            if (failCount < 10) {
                console.log(`Token ${i} (${token}) -> Decoded: "${decodedStr}" (No digits!)`);
            }
            failCount++;
            continue;
        }
        
        const num = parseInt(digitsOnly, 10) - rValue;
        if (successCount < 30) {
            console.log(`Token ${i} (${token}) -> Decoded: "${decodedStr}" -> Digits: "${digitsOnly}" -> Char: ${num} ("${String.fromCharCode(num)}")`);
        }
        successCount++;
    } catch (e) {
        failCount++;
    }
}

console.log(`\nDecryption Summary: Success: ${successCount}, Fail: ${failCount}`);
