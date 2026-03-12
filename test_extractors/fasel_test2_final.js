// Final extraction - properly handle the URL joining
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');
const lines = html.split('\n');

// Find the target line
const targetLine = lines.find(l => l.includes('_0x') && l.includes("document['write']"));
if (!targetLine) {
    console.log("ERROR: No target line");
    process.exit(1);
}

// Find the start of document['write']
const docWriteStart = targetLine.indexOf("document['write']");
const remaining = targetLine.substring(docWriteStart);

// Find the opening ( after document['write']
const openParen = remaining.indexOf('(');
if (openParen < 0) {
    console.log("No opening paren");
    process.exit(1);
}

// Find the matching closing );
let parenCount = 0;
let argEnd = -1;
for (let i = openParen; i < remaining.length; i++) {
    if (remaining[i] === '(') parenCount++;
    else if (remaining[i] === ')') {
        parenCount--;
        if (parenCount === 0) {
            argEnd = i;
            break;
        }
    }
}

if (argEnd < 0) {
    console.log("Could not find closing paren");
    process.exit(1);
}

const writeArg = remaining.substring(openParen + 1, argEnd);

// Join all literals (removing quotes)
const literals = writeArg.match(/'[^']*'/g);
let joined = literals.map(s => s.slice(1, -1)).join('');

// Unescape hex
joined = joined.replace(/\\x([0-9a-fA-F]{2})/g, (match, hex) => {
    return String.fromCharCode(parseInt(hex, 16));
});

console.log("=== After basic join (first 500 chars) ===");
console.log(joined.substring(0, 500));

// The issue: JS concatenation adds "//" but we lose it when joining literals
// Fix: add // after https: to get proper URLs
let fixed = joined.replace(/https:/g, 'https://');

console.log("\n=== After fixing https: to https:// ===");
console.log(fixed.substring(0, 500));

// Now extract URLs
const m3u8Urls = fixed.match(/https:\/\/[^\s"'<>]+\.m3u8/g);
if (m3u8Urls) {
    console.log("\n=== FOUND M3U8 URLs ===");
    m3u8Urls.forEach((u, i) => console.log(`${i+1}. ${u}`));
}

// Also extract data-url attributes - these have the quality buttons
const dataUrls = fixed.match(/data-url="https:\/\/[^"]+"/g);
if (dataUrls) {
    console.log("\n=== Found data-url attributes ===");
    dataUrls.forEach((u, i) => console.log(`${i+1}. ${u}`));
}

// The data-url format is: data-url="https://<path>"
// Let's also look for partial data-urls (without https://)
const partialDataUrls = fixed.match(/data-url="h\.[^"]+"/g);
if (partialDataUrls) {
    console.log("\n=== Partial data-url (needs https:// prefix) ===");
    partialDataUrls.forEach((u, i) => {
        console.log(`${i+1}. https://${u.replace('data-url="h.', 'h.')}`);
    });
}
