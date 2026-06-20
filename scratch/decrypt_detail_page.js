const fs = require('fs');

const html = fs.readFileSync('cimanow_detail.html', 'utf8');

// Find the definition of the obfuscated variable
const splitMatch = /var\s+_parts\s*=\s*(\w+)\.split/.exec(html);
if (!splitMatch) {
    console.error("Could not find split variable name in detail page");
    process.exit(1);
}
const varName = splitMatch[1];
console.log("Obfuscated variable name in detail:", varName);

const varRegex = new RegExp(`var\\s+${varName}\\s*=\\s*([\\s\\S]*?);`);
const varMatch = varRegex.exec(html);
if (!varMatch) {
    console.error(`Could not find definition of ${varName} in detail page`);
    process.exit(1);
}
const assignmentContent = varMatch[1];

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
    console.error("Failed to resolve _r value");
    process.exit(1);
}

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
    } catch (e) {}
}

const decryptedHtml = Buffer.from(decryptedBytes).toString('utf8');
fs.writeFileSync('cimanow_detail_decrypted.html', decryptedHtml);
console.log(`Decrypted detail HTML length: ${decryptedHtml.length}`);

// Find download links
const dlRegex = /<a[^>]+href=["'](https?:\/\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
let dlMatch;
console.log("\nSearching for links in decrypted detail HTML:");
let dlCount = 0;
while ((dlMatch = dlRegex.exec(decryptedHtml)) !== null) {
    const url = dlMatch[1];
    const text = dlMatch[2].replace(/<[^>]+>/g, '').trim();
    if (url.includes('download') || text.includes('تحميل') || text.includes('Quality') || text.match(/\d{3,4}p/)) {
        console.log(`Link: ${text} -> ${url}`);
        dlCount++;
    }
}
console.log(`Total download/quality links found: ${dlCount}`);
