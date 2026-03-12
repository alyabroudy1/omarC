// Extract data-url attributes which should have complete URLs
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

console.log("=== Looking for data-url attributes ===");

// Find data-url patterns
const dataUrlPattern = joined.match(/data-url="h\.faselhdx\.[^"]+"/g);
if (dataUrlPattern) {
    console.log("\nFound data-url patterns:", dataUrlPattern.length);
    dataUrlPattern.forEach((p, i) => console.log(`${i+1}. ${p}`));
}

// Let me try a different approach - look for patterns with the domain
const allDataUrls = joined.match(/data-url="h[^"]+"/g);
if (allDataUrls) {
    console.log("\nAll h data-urls:", allDataUrls.length);
    allDataUrls.forEach((p, i) => console.log(`${i+1}. ${p}`));
}

// Let's also look for patterns in the original concatenated strings
// The issue is that the literals are being concatenated incorrectly
// Let's look at each literal separately

console.log("\n=== Individual literals ===");
const individualLiterals = writeArg.match(/'[^']*'/g);
if (individualLiterals) {
    individualLiterals.forEach((lit, i) => {
        const clean = lit.slice(1, -1);
        console.log(`${i}: ${clean}`);
    });
}

// Actually - I notice the issue now. Looking at the pattern:
// data-url="h.faselhdx.es/DE/0/..." 
// The full URL should be: https://h.faselhdx.es/DE/0/...

// Let me try building the URL properly
console.log("\n=== Building proper URLs ===");

// Look for the pattern that should be the base URL
// The base should be: https://<something>.faselhdx.xyz/...

// Let me extract from the joined string more carefully
const urlPattern = joined.match(/data-url="h\.[^"]+"/);
if (urlPattern) {
    console.log("URL pattern found:", urlPattern[0]);
    
    // Extract the path
    const pathMatch = urlPattern[0].match(/data-url="h\.(faselhdx\.[^"]+)"/);
    if (pathMatch) {
        console.log("Path:", pathMatch[1]);
        console.log("Full URL should be: https://h." + pathMatch[1]);
    }
}

// Actually, looking more carefully at the structure:
// The URL parts are split across the literals in an unusual way
// Let me look at what the original pattern should be

// Looking at: data-url="h.faselhdx.es/DE/0/0855_hd720b_..."
// This seems like a partial URL that's missing the protocol

// Let me search for any complete URLs in the HTML
console.log("\n=== Searching for complete URLs in original HTML ===");
const completeUrls = html.match(/https:\/\/[^"'\s]+\.m3u8/g);
if (completeUrls) {
    console.log("Complete URLs:", completeUrls);
}
