// Fix the extraction - the issue is the regex needs to capture all quote types
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');
const lines = html.split('\n');

// Find the target line
const targetLine = lines.find(l => l.includes('_0x') && l.includes("document['write']"));
if (!targetLine) {
    console.log("ERROR: No target line");
    process.exit(1);
}

// Find document.write call
const writeIdx = targetLine.indexOf("document['write']");
const afterWrite = targetLine.substring(writeIdx);

// Extract ALL string literals (single AND double quoted)
const allLiterals = afterWrite.match(/(["'])(?:(?!\1)[^\\]|\\.)*\1/g);
console.log("Total literals found:", allLiterals?.length);

// Also try just single quotes 
const singleLiterals = afterWrite.match(/'[^']*'/g);
console.log("Single-quoted literals:", singleLiterals?.length);

// Try to find the specific pattern for URLs
// Looking at the output: rl="https:stream/v2/..."
// The URL parts should be:
// https:stream/v2/ = base
// 9UkWn2XHwK = subdomain
// Iw/1773342 = path part 1
// /0/5.42.20 = quality
// ffab002464 = some ID
// /web31212x = server
// .m3u8 = extension

// Let's look at the structure more carefully
console.log("\n=== Looking at structure ===");

// Find the actual concatenation pattern in the HTML
const concatPattern = targetLine.match(/document\['write'\]\([^)]+\)/);
if (concatPattern) {
    console.log("Document write call found, length:", concatPattern[0].length);
    
    // Extract string parts from the concat
    const parts = concatPattern[0].match(/'[^']+'/g);
    console.log("Parts in concat:", parts?.length);
    
    if (parts) {
        // Join the parts
        let url = parts.join('');
        console.log("\nJoined parts:", url.substring(0, 500));
        
        // Check for URLs
        const urls = url.match(/https?:\/\/[^\s"'>]+/g);
        if (urls) {
            console.log("\n=== FOUND URLS ===");
            urls.forEach(u => console.log(u));
        }
    }
}

// Let's try a different approach - look at the raw pattern for URL building
console.log("\n=== Looking for URL pattern in source ===");

// The URL is built like: 'https:' + 'stream/v2/' + '9UkWn2XHwK' + ...
// We need to find the concatenation

// Try extracting using a broader pattern
const broaderMatch = targetLine.match(/document\['write'\]\((.+)\);$/s);
if (broaderMatch) {
    let arg = broaderMatch[1];
    console.log("Argument length:", arg.length);
    
    // Extract all string literals
    const strings = arg.match(/(".*?"|'.*?')/g);
    console.log("String count:", strings?.length);
    
    if (strings) {
        // Join all strings
        let joined = strings.map(s => s.slice(1, -1)).join('');
        console.log("\nJoined length:", joined.length);
        
        // Find URLs
        const urlMatch = joined.match(/https:[^>]+m3u8/);
        if (urlMatch) {
            console.log("\n=== FOUND URL ===");
            console.log(urlMatch[0]);
        }
    }
}
