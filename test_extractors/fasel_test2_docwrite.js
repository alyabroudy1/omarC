// Test LiteralConcatStrategy approach for this new URL
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');
const lines = html.split('\n');

// Find the target line
const targetLine = lines.find(l => l.includes('_0x') && l.includes("document['write']"));
if (!targetLine) {
    console.log("No document.write line found");
    process.exit(1);
}

console.log("Found target line, length:", targetLine.length);

// Find document.write call
const writeIdx = targetLine.indexOf("document['write']");
if (writeIdx < 0) {
    console.log("No document.write found");
    process.exit(1);
}

const afterWrite = targetLine.substring(writeIdx);

// Extract all single-quoted string literals
const literals = afterWrite.match(/'([^']*)'/g);
console.log("Found literals:", literals?.length);

if (literals && literals.length > 0) {
    // Join and unescape hex
    let joined = literals.join('').replace(/\\x([0-9a-fA-F]{2})/g, (match, hex) => {
        return String.fromCharCode(parseInt(hex, 16));
    });
    
    console.log("\n=== Joined string (first 2000 chars) ===");
    console.log(joined.substring(0, 2000));
    
    // Look for m3u8 URLs
    const m3u8Urls = joined.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
    if (m3u8Urls) {
        console.log("\n=== M3U8 URLs found! ===");
        m3u8Urls.forEach((u, i) => console.log(`${i+1}. ${u}`));
    }
    
    // Also look for data-url attributes
    const dataUrls = joined.match(/data-url="([^"]+)"/g);
    if (dataUrls) {
        console.log("\n=== data-url attributes ===");
        dataUrls.forEach((u, i) => console.log(`${i+1}. ${u}`));
    }
}
