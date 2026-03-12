// Implement the decoder manually to extract the URL
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// The URL is obfuscated. Let me try a simpler approach - 
// just look at what we need to decode and try to understand the pattern

// Find where the hlsPlaylist sources are defined
const sourcesStart = html.indexOf("_0x5e3ffb['sources']=[");
if (sourcesStart >= 0) {
    const sourcesSection = html.substring(sourcesStart, sourcesStart + 500);
    console.log("Sources section:", sourcesSection.slice(0, 300));
}

// Find where 'file' property is set
const fileStart = html.indexOf("_0x5e3ffb['file']=");
if (fileStart >= 0) {
    const fileSection = html.substring(fileStart, fileStart + 800);
    console.log("\nFile section:", fileSection.slice(0, 400));
    
    // Extract the numeric arguments from the function calls
    const args = fileSection.match(/0x[0-9a-f]+/g);
    console.log("\nNumeric args in file:", args?.length);
    if (args) {
        console.log("First 30:", args.slice(0, 30));
    }
}

// Let's try to manually decode by understanding the decode function
// The key function is _0x14e4 which does Base64-like decode

// Find the Base64 alphabet in the code
const base64Match = html.match(/'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+\/='/);
if (base64Match) {
    console.log("\nBase64 alphabet found");
}

// Let me find the exact decode logic
// Looking at _0x14e4, it's a custom Base64 decoder

// Extract the string table - it's at the beginning of the IIFE
const tableStart = html.indexOf("var _0x56e007='");
if (tableStart >= 0) {
    const tableSection = html.substring(tableStart, tableStart + 100);
    console.log("\nTable section:", tableSection);
}

// Let me try to find what's in the decode by looking for known URL parts
console.log("\n=== Searching for decoded values ===");

// The URL likely contains: https://<something>.scdns.io or .faselhdx.xyz
// Search for "faselhdx" as it would appear in decoded form somewhere
const faselhdxParts = html.match(/faselhdx/gi);
console.log("faselhdx occurrences:", faselhdxParts?.length);

// Try to find the subdomain pattern
const subdomainPattern = html.match(/[a-z0-9]+\.scdns\./gi);
console.log("scdns subdomains:", subdomainPattern);

// Let's try extracting the exact URL by looking at what calls are made
// The file URL is built by concatenating multiple decoded strings

// Look for the complete _0x5e3ffb object definition
const objStart = html.indexOf("var _0x5e3ffb={}");
const objEnd = html.indexOf("var hlsPlaylist=");
if (objStart >= 0 && objEnd >= 0) {
    const objSection = html.substring(objStart, objEnd);
    console.log("\n=== Object section (first 500) ===");
    console.log(objSection.slice(0, 500));
}

// The decode is complex. Let me try a different approach:
// Just find any URL pattern in the raw HTML that could be the stream

console.log("\n=== Looking for complete URL patterns ===");
// Look for patterns like: https://xxx/yyy/zzz.m3u8
const fullUrlPattern = html.match(/https:\/\/[a-z0-9\-\.]+\/[^\s'"<>]+\.m3u8/gi);
console.log("Full m3u8 URLs:", fullUrlPattern);

// Let me try to extract and run just the specific decode calls needed
console.log("\n=== Attempting targeted extraction ===");

// Get the exact arguments for the first few characters of the URL
const firstFileCall = html.match(/_0x5e3ffb\['file'\]=((?:_0x[a-f0-9]+\([^)]+\)\+?)+)/);
if (firstFileCall) {
    console.log("File building:", firstFileCall[1].slice(0, 300));
    
    // Extract each function call
    const calls = firstFileCall[1].match(/_0x[a-f0-9]+\([^)]+\)/g);
    console.log("\nTotal calls:", calls?.length);
    
    // The first few calls should give us the start of the URL (https://)
    if (calls && calls.length > 0) {
        console.log("First 5 calls:");
        calls.slice(0, 5).forEach((c, i) => console.log(`  ${i}: ${c}`));
    }
}
