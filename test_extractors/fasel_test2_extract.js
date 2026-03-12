// Better extraction - handle the full document.write argument
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

// Find the matching closing ); - we need to track nested parens
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
console.log("Write argument length:", writeArg.length);

// Now extract all string literals from the argument
const literals = writeArg.match(/'[^']*'/g);
console.log("Literals found:", literals?.length);

if (literals && literals.length > 0) {
    // Join all literals (removing quotes)
    let joined = literals.map(s => s.slice(1, -1)).join('');
    
    // Also unescape hex sequences
    joined = joined.replace(/\\x([0-9a-fA-F]{2})/g, (match, hex) => {
        return String.fromCharCode(parseInt(hex, 16));
    });
    
    console.log("\n=== Joined string (first 1000 chars) ===");
    console.log(joined.substring(0, 1000));
    
    // The URL is malformed because when you join literals, 
    // you lose the JS concatenation that adds "://"
    // Looking at: rl="https:stream/v2/" 
    // The actual URL should have // after https:
    
    // Let's try adding // after https: to fix it
    let fixedUrl = joined.replace(/https:/g, 'https://');
    
    // Find URLs
    const urls = fixedUrl.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
    if (urls) {
        console.log("\n=== FOUND M3U8 URLs ===");
        urls.forEach((u, i) => console.log(`${i+1}. ${u}`));
    }
    
    // Look for data-url attributes in original
    const dataUrls = joined.match(/data-url="([^"]+)"/g);
    if (dataUrls) {
        console.log("\n=== Found data-url attributes ===");
        dataUrls.forEach((m, i) => {
            const match = m.match(/data-url="([^"]+)"/);
            if (match) {
                console.log(`  URL ${i+1}: ${match[1]}`);
            }
        });
    }
}
