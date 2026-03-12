// Final extraction with proper data-url handling
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');
const lines = html.split('\n');

// Find the target line
const targetLine = lines.find(l => l.includes('_0x') && l.includes("document['write']"));

// Extract write argument
const docWriteStart = targetLine.indexOf("document['write']");
const remaining = targetLine.substring(docWriteStart);
const openParen = remaining.indexOf('(');

let parenCount = 0;
let argEnd = -1;
for (let i = openParen; i < remaining.length; i++) {
    if (remaining[i] === '(') parenCount++;
    else if (remaining[i] === ')') {
        parenCount--;
        if (parenCount === 0) { argEnd = i; break; }
    }
}

const writeArg = remaining.substring(openParen + 1, argEnd);
const literals = writeArg.match(/'[^']*'/g);
let joined = literals.map(s => s.slice(1, -1)).join('');
joined = joined.replace(/\\x([0-9a-fA-F]{2})/g, (match, hex) => String.fromCharCode(parseInt(hex, 16)));

// Fix: add // after https:
let fixed = joined.replace(/https:/g, 'https://');

console.log("=== Extracted URLs ===\n");

// Look for m3u8 URLs
const m3u8Urls = fixed.match(/https:\/\/[^"'\s<>]+\.m3u8/g);
if (m3u8Urls) {
    console.log("M3U8 URLs (primary):");
    m3u8Urls.forEach((u, i) => console.log(`  ${i+1}. ${u}`));
}

// Look for data-url attributes with the full path
// Pattern: data-url="https://<domain>/<path>"
const dataUrls = fixed.match(/data-url="https:\/\/[^"]+"/g);
if (dataUrls) {
    console.log("\nData-url attributes:");
    dataUrls.forEach((u, i) => console.log(`  ${i+1}. ${u}`));
}

// Also look for partial data-urls that need the prefix
const partialDataUrls = fixed.match(/data-url="h\.[^"]+"/g);
if (partialDataUrls) {
    console.log("\nPartial data-urls (needs https:// prefix):");
    partialDataUrls.forEach((u, i) => {
        // The pattern is: data-url="h.faselhdx.es/..."
        // Full URL should be: https://h.faselhdx.es/...
        const fullUrl = 'https://' + u.replace('data-url="h.', 'h.');
        console.log(`  ${i+1}. ${fullUrl}`);
    });
}

console.log("\n=== Extraction Complete ===");
console.log("The extractor should find these URLs using:");
console.log("1. Fix 'https:' -> 'https://' in the joined string");
console.log("2. Find m3u8 URLs with regex");
console.log("3. Also check data-url attributes for quality buttons");
