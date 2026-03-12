// Manually extract and decode the strings
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// Extract the string table from _0x51c0
const tableMatch = html.match(/var _0x56e007=\[([^\]]+)\];/);
if (!tableMatch) {
    console.log("Could not find string table");
    process.exit(1);
}

// Parse the string array
const rawStrings = tableMatch[1].match(/"[^"]+"/g);
console.log("Found", rawStrings.length, "raw strings");

// Extract the string table indices from _0x14e4
// The function uses _0x51c0() to get strings and does Base64-like decoding

// Let's find where the URLs are actually decoded in the final output
// by looking at what _0x373126 and _0x492df0 return

// Look for specific URL patterns in the decoded strings
// The URL should contain: scdns or faselhdx or m3u8

// Extract the decode function _0x14e4
const decodeFuncMatch = html.match(/function _0x14e4\(_0x35839d,_0x2c4deb\){[^}]+}/);
if (decodeFuncMatch) {
    console.log("\nFound decode function");
}

// The decode function uses a string from _0x56e007 (the table)
// Let's try to find the URL directly by searching in the final hlsPlaylist

// Look at the _0x5e3ffb object - this is where the playlist is built
const playlistStart = html.indexOf('_0x5e3ffb={}');
const playlistChunk = html.substring(playlistStart, playlistStart + 2000);
console.log("\nPlaylist chunk (first 500 chars):");
console.log(playlistChunk.substring(0, 500));

// Find the file URL by looking for patterns that would result in an m3u8 URL
// Look for the base URL - it starts with https://
const httpsMatch = html.match(/'https:\/\/[^']+'/);
if (httpsMatch) {
    console.log("\nFound https URL pattern:", httpsMatch[0]);
}

// Let me try a different approach - look at what _0x373126(0x335, 0x361, ... would decode to
// by finding what the function does

console.log("\n=== Trying to find decoded strings ===");

// The decode function _0x14e4 takes an index and decodes using Base64
// Let's try to find the encoded parts by looking at function calls

// Look for _0x373126 calls which seem to build URLs
const urlBuildCalls = html.match(/_0x373126\([^)]+\)/g);
if (urlBuildCalls) {
    console.log("\nFound", urlBuildCalls.length, "calls to _0x373126");
    console.log("First 5:", urlBuildCalls.slice(0, 5));
}

// Let's extract the actual decoded values by running a simplified version
// The key is that _0x14e4(n, key) returns decoded string at position n

// Extract string table
const strings = rawStrings.map(s => s.slice(1, -1)); // remove quotes

// The decode function _0x14e4 is complex - let's try another approach
// Look for the final URL in the jwplayer config directly

// Find where the sources array is defined
const sourcesMatch = html.match(/_0x5e3ffb\[_0x[0-9a-f]+\([^,]+,[^)]+\)\]=/);
if (sourcesMatch) {
    console.log("\nSources assignment found");
}

// Look for patterns that result in "file" property
const filePropPattern = html.match(/_0x5e3ffb\['file'\]=[^;]+/);
if (filePropPattern) {
    console.log("\nFile property:", filePropPattern[0].slice(0, 200));
}

// Let me try to manually find what the decoded string would be
// by analyzing the _0x14e4 function

// The key pattern: _0x14e4 takes (index, offset) and returns decoded char
// Looking at the function: it uses _0x56e007 (the string table) and does Base64 decode

// Let's find specific values that would decode to known parts
// Look for "https://" which would be encoded

// Extract the key parts from the config
console.log("\n=== Looking for image URL (easier to find) ===");
const imageMatch = html.match(/_0x5e3ffb\['image'\]='([^']+)'/);
if (imageMatch) {
    console.log("Image URL pattern:", imageMatch[1]);
}

// Actually, let's just manually decode the URL from the obfuscated data
// The URL is built by concatenating multiple _0x373126 and _0x492df0 calls

// Find all the parts that make up the URL
const urlPartsMatch = html.match(/_0x5e3ffb\['file'\]=(_0x[0-9a-f]+\([^)]+\))\+(_0x[0-9a-f]+\([^)]+\))\+[^;]+/);
if (urlPartsMatch) {
    console.log("\nURL parts:", urlPartsMatch[0].slice(0, 300));
}

// Let's try a simpler approach - use Function constructor to create a working decode
console.log("\n=== Trying to extract the decoded URL manually ===");

// Find the line that sets 'file' property
const fileLine = html.match(/_0x5e3ffb\['file'\]=[^;]+;/);
if (fileLine) {
    console.log("File line:", fileLine[0]);
}
