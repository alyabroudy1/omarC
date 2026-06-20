const fs = require('fs');

const html = fs.readFileSync('cimanow_watch_noon.html', 'utf8');

// 1. Find the split variable name
const splitMatch = /var\s+_parts\s*=\s*(\w+)\.split/.exec(html);
if (!splitMatch) {
    console.error("Could not find split variable name");
    process.exit(1);
}
const varName = splitMatch[1];
console.log("Obfuscated variable name:", varName);

// 2. Find the definition of this variable
const varRegex = new RegExp(`var\\s+${varName}\\s*=\\s*([\\s\\S]*?);`);
const varMatch = varRegex.exec(html);
if (!varMatch) {
    console.error(`Could not find definition of ${varName}`);
    process.exit(1);
}
const assignmentContent = varMatch[1];

// 3. Extract all quoted chunks from the assignment content
const chunkRegex = /['"]([A-Za-z0-9+/=~]+)['"]/g;
let chunks = '';
let match;
let count = 0;
while ((match = chunkRegex.exec(assignmentContent)) !== null) {
    chunks += match[1];
    count++;
}
console.log(`Found ${count} chunks in assignment.`);
const obfuscatedData = chunks.replace(/[\r\n\t'+\s]/g, '');
console.log(`Obfuscated data length: ${obfuscatedData.length}`);

// 4. Extract _r formula
const rMatch = /var\s+_r\s*=\s*([\d\s+\-*\/()]+);/.exec(html);
let rValue = null;
if (rMatch) {
    const formula = rMatch[1];
    console.log(`Found formula: ${formula}`);
    rValue = eval(formula);
} else {
    // Literal match fallback
    const literalMatch = /-\s*(\d+)\s*\)\s*;\s*\}\s*\)\s*;/.exec(html)
        || /-\s*(\d+)\s*\)\s*;\s*\}\s*\)\s*;\s*document\.open/.exec(html)
        || /-\s*(\d+)\s*\)\s*;/.exec(html);
    if (literalMatch) {
        rValue = parseInt(literalMatch[1], 10);
    }
}

console.log(`Resolved _r: ${rValue}`);

if (rValue === null) {
    console.error("Failed to resolve _r value");
    process.exit(1);
}

// 5. Decrypt tokens
const tokens = obfuscatedData.split('~');
const decryptedBytes = [];
for (const token of tokens) {
    if (!token) continue;
    try {
        let padded = token;
        while (padded.length % 4 !== 0) {
            padded += '=';
        }
        const decodedStr = Buffer.from(padded, 'base64').toString('utf8');
        const digitsOnly = decodedStr.replace(/\D/g, '');
        if (!digitsOnly) continue;
        const num = parseInt(digitsOnly, 10) - rValue;
        decryptedBytes.push(num);
    } catch (e) {
        // Ignore malformed tokens
    }
}

const decryptedHtml = Buffer.from(decryptedBytes).toString('utf8');
fs.writeFileSync('cimanow_watch_noon_decrypted.html', decryptedHtml);
console.log(`Decrypted html length: ${decryptedHtml.length}`);

// Find download links
const dlRegex = /<a[^>]+href=["'](https?:\/\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
let dlMatch;
console.log("\nSearching for download links in decrypted html:");
while ((dlMatch = dlRegex.exec(decryptedHtml)) !== null) {
    const url = dlMatch[1];
    const text = dlMatch[2].replace(/<[^>]+>/g, '').trim();
    if (url.includes('download') || text.includes('تحميل') || text.includes('Quality') || text.match(/\d{3,4}p/)) {
        console.log(`Link: ${text} -> ${url}`);
    }
}
