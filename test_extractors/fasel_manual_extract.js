// Manual extraction - extract the string table and decode
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// The URL is built from parts. Let's find the actual URL by searching for patterns
// Looking at the _0x5e3ffb object which contains 'file' property

// Find the section where the file URL is built
const fileSection = html.match(/_0x5e3ffb\['file'\][^;]+;/);
if (fileSection) {
    console.log("File section:", fileSection[0].slice(0, 500));
}

// Let's find the _0x373126 function to understand how it decodes
// Look for the function definition
const func373126 = html.match(/function _0x373126\([^)]+\){[^}]+}/);
if (func373126) {
    console.log("\n_0x373126 function found");
}

// The decode function is _0x14e4 - it's a Base64-like decoder
// Let's extract the string table _0x56e007

// Find the string array
const stringTableMatch = html.match(/var _0x56e007=\[([^\]]+)\];/);
if (stringTableMatch) {
    console.log("\nFound string table, length:", stringTableMatch[1].length);
}

// Let's try to decode specific values manually
// The URL typically contains: https://<subdomain>.scdns.io or https://<something>.faselhdx.xyz

// Try to find the CDN domain in the obfuscated data
// Look for patterns like "scdns" or "faselhdx" in the final decoded strings

// Actually, let's try to find the URL by looking at the pattern of string building
// Search for 'https' or 'http' in the deobfuscation

console.log("\n=== Looking for URL patterns ===");

// Find all the encoded string values that look like they could be URL parts
// Look for the _0x373126 and _0x492df0 calls that build URLs

const urlParts = [];
// Look for patterns that would decode to known CDN domains
const domainPatterns = ['scdns', 'faselhdx', 'm3u8', 'cloudfront', 'cdn'];

for (const pattern of domainPatterns) {
    // Search in the raw HTML for these patterns (they might be in the decoded form somewhere)
    const idx = html.indexOf(pattern);
    if (idx >= 0) {
        console.log(`Found '${pattern}' at position ${idx}`);
    }
}

// Let's try extracting the actual decoded URL by running just the string decode function
// Extract the _0x14e4 function which is the core decoder

console.log("\n=== Extracting decoder ===");

const decodeFuncStart = html.indexOf('function _0x14e4');
const decodeFuncEnd = html.indexOf('var _0x443697');
if (decodeFuncStart >= 0 && decodeFuncEnd >= 0) {
    const decodeFunc = html.substring(decodeFuncStart, decodeFuncEnd);
    console.log("Decode function length:", decodeFunc.length);
    
    // Extract the string table
    const tableStart = decodeFunc.indexOf('var _0x40f338=_0x51c0()');
    if (tableStart >= 0) {
        // The strings are at the beginning in _0x51c0
        const tableSection = decodeFunc.substring(0, 200);
        console.log("Table section:", tableSection.slice(-100));
    }
}

// Let's try a simpler approach - look for the final URL directly in the final playlist output
// by analyzing what the playlist object contains

// Look for the image URL which is easier to find
const imagePattern = html.match(/_0x5e3ffb\['image'\]='([^']+)'/);
if (imagePattern) {
    console.log("\nImage URL pattern:", imagePattern[1]);
}

// Try to manually decode the URL by looking at the string building blocks
// The URL: https://<part1><part2>/.../master.m3u8

// Find all parts that get concatenated
const fileParts = html.match(/_0x5e3ffb\['file'\]=([^;]+);/g);
if (fileParts) {
    console.log("\nFile parts found:", fileParts.length);
    console.log("Sample:", fileParts[0]?.slice(0, 200));
}

// Let's look at the actual call that builds the URL in detail
const fullFileAssign = html.match(/_0x5e3ffb\['file'\][^;]{0,500}/);
if (fullFileAssign) {
    console.log("\nFull assignment:", fullFileAssign[0]);
}

// Let me try extracting by looking at what _0x373126 and _0x492df0 decode to
// These functions take numeric arguments and return decoded characters

// Find the actual call values in the file assignment
const callsInFile = html.match(/_0x5e3ffb\['file'\]=(_0x\([^)]+\)\+_0x\([^)]+\)\+[^;]+)/);
if (callsInFile) {
    console.log("\n=== URL building calls ===");
    console.log(callsInFile[1].slice(0, 300));
}

// Let me try one more thing - use a timeout on eval but with a simpler extraction
console.log("\n=== Trying simplified extraction ===");

// Just extract what we can see directly
// Look for any .m3u8 in the whole HTML
const allM3u8 = html.match(/https?:\/\/[^\s"']+\.m3u8/g);
console.log("M3U8 URLs found:", allM3u8?.length);
if (allM3u8) {
    allM3u8.forEach(u => console.log(" -", u));
}
