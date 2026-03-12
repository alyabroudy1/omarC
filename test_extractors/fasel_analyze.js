// Look more carefully at the data-url format
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');

// The data-url format: data-url="https://<something>/..."
// Let's find any complete URLs in the data-url format

// Look for patterns that include data-url in the HTML
const dataUrlMatches = html.match(/data-url="https:[^"]+"/g);
console.log("Complete data-urls with https:", dataUrlMatches?.length);

if (dataUrlMatches) {
    dataUrlMatches.forEach((m, i) => console.log(`${i+1}. ${m}`));
}

// Look for patterns that might have the URL in a different format
// The URL might be built as: 'data-url="' + 'https:' + '//' + subdomain + domain + ...

// Let's also look for any CDN domain patterns in the original
console.log("\n=== Looking for CDN patterns ===");
const cdnPatterns = ['scdns', 'faselhdx', 'stream', 'video'];
for (const p of cdnPatterns) {
    const idx = html.indexOf(p);
    if (idx >= 0) {
        console.log(`Found '${p}' at position ${idx}`);
        console.log(`  Context: ...${html.substring(idx-20, idx+50)}...`);
    }
}

// Actually, let me re-examine the literals more carefully
// to understand the proper URL construction

const lines = html.split('\n');
const targetLine = lines.find(l => l.includes('_0x') && l.includes("document['write']"));
const docWriteStart = targetLine.indexOf("document['write']");
const remaining = targetLine.substring(docWriteStart);
const openParen = remaining.indexOf('(');

let parenCount = 0;
let argEnd = -1;
for (let i = openParen; i < remaining.length; i++) {
    if (remaining[i] === '(') parenCount++;
    else if (remaining[i] === ')') { parenCount--; if (parenCount === 0) { argEnd = i; break; } }
}

const writeArg = remaining.substring(openParen + 1, argEnd);
const literals = writeArg.match(/'[^']*'/g);

// Each literal (cleaned)
console.log("\n=== Literals analysis ===");
literals.forEach((lit, i) => {
    const clean = lit.slice(1, -1);
    console.log(`${i}: "${clean}"`);
    
    // Look for patterns that might help us understand the URL structure
    if (clean.includes('http')) console.log(`  ^ contains http`);
    if (clean.includes('m3u8')) console.log(`  ^ contains m3u8`);
    if (clean.includes('data-url')) console.log(`  ^ contains data-url`);
});
