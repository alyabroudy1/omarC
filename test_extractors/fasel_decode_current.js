// Test script to decode FaselHD jwplayer stream
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// Find the script that contains hlsPlaylist definition
// We need to extract the obfuscated parts and decode them

// Look for the pattern: var hlsPlaylist = {...}
const hlsMatch = html.match(/var hlsPlaylist=([^,;]+)/);
if (!hlsMatch) {
    console.log("Could not find hlsPlaylist");
    process.exit(1);
}

// The hlsPlaylist is built from obfuscated components
// We need to evaluate the JS to get the actual playlist

// Find all the obfuscation helper functions
// The key function is _0x14e4 which does Base64-like decoding

// Let's extract the relevant script block
const scriptStart = html.indexOf('function _0x14e4');
const scriptEnd = html.indexOf('var hlsPlaylist', scriptStart);
const relevantScript = html.substring(scriptStart, scriptEnd);

// Extract the string array that _0x14e4 uses
const stringArrayMatch = relevantScript.match(/_0x51c0=function\(\)\{var _0x56e007=\[([^\]]+)\]/);
if (!stringArrayMatch) {
    console.log("Could not find string array");
    process.exit(1);
}

console.log("Found obfuscated script, attempting to decode...");

// We need to extract and run the deobfuscation logic
// Let's try a simpler approach - find the final m3u8 URL in the decoded output

// Try to find the part that builds the file URL
const fileObjMatch = html.match(/\{[^}]*'file'[^}]*\}/);
if (fileObjMatch) {
    console.log("Found file object structure");
}

// Let's look for patterns that contain "m3u8" in the final decoded form
// by searching for the string concatenation parts

// Another approach: find the base URL parts
const baseUrlParts = html.match(/'https:\/\/[^']+m3u8'/g);
if (baseUrlParts) {
    console.log("Found m3u8 URL parts:", baseUrlParts);
}

// Let's try extracting and evaluating the specific deobfuscation
// The key is to find where the file URL is constructed

// Find the _0xa54bc1 object which contains 'file' property
const fileObjStart = html.indexOf('_0xa54bc1={}');
if (fileObjStart >= 0) {
    // Extract a reasonable chunk
    const chunk = html.substring(fileObjStart, fileObjStart + 3000);
    console.log("\nFile object chunk:");
    console.log(chunk.substring(0, 500));
}

// More direct: let's extract the Base64-encoded parts and decode them
// The URL pattern seems to be: https://<base>/<path>/master.m3u8

// Find the obfuscation key array
const keys = html.match(/_0x56e007=\[([^\]]+)\]/);
if (keys) {
    console.log("\nFound keys array");
}

// Try to find the actual URL by looking for "scdns" which is their CDN
const scdnsMatch = html.match(/scdns[^<]*/);
if (scdnsMatch) {
    console.log("\nSCDNS match:", scdnsMatch[0].substring(0, 100));
}

// Let's just extract the entire relevant script and eval it
// Find the start of the obfuscation IIFE
const iifeStart = html.indexOf('(function(_0x32e62d');
if (iifeStart >= 0) {
    // Find where hlsPlaylist is assigned
    const hlsAssign = html.indexOf('var hlsPlaylist=');
    if (hlsAssign >= 0) {
        const jsToEval = html.substring(iifeStart, hlsAssign + 500);
        
        try {
            // Create a minimal environment
            const evalScript = `
                var window = this;
                var document = { write: function() {}, createElement: function() { return { src: '', setAttribute: function() {} }; }, head: {}, body: {} };
                var mainPlayer = { setup: function() {}, on: function() {} };
                var console = { log: function() {}, error: function() {} };
                
                ${jsToEval}
                
                // After execution, hlsPlaylist should be defined
                if (typeof hlsPlaylist !== 'undefined') {
                    console.log(JSON.stringify(hlsPlaylist));
                } else {
                    console.log('hlsPlaylist not defined');
                }
            `;
            
            // We can't directly eval in Node, let's try a different approach
            // Extract the Base64 string manually
            
            // Look for the encoded URL parts
            const encodedParts = html.match(/_0x373126\([^)]+\)\+_0x492df0\([^)]+\)/g);
            if (encodedParts) {
                console.log("\nEncoded parts found:", encodedParts.length);
            }
        } catch(e) {
            console.log("Error:", e.message);
        }
    }
}

// Simplest approach: search for the actual m3u8 URL pattern in the decoded strings
// The URL should be something like: https://<something>.scdns.io/<path>/master.m3u8
console.log("\n--- Looking for final URL pattern ---");

// The URL is built from multiple obfuscated strings
// Let's find what _0x373126 and _0x492df0 decode to

// Extract the string table
const tableMatch = html.match(/var _0x56e007=\[([^\]]+)\];_0x51c0=function/);
if (tableMatch) {
    console.log("Found string table");
    // Parse the strings
    const strings = tableMatch[1].match(/"[^"]+"/g);
    if (strings) {
        console.log("First few strings:", strings.slice(0, 10));
    }
}
