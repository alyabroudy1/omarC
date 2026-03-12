// Test the extraction with Node.js to understand the new format
const fs = require('fs');
const vm = require('vm');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// Find the relevant JavaScript block
const lines = html.split('\n');
const targetLine = lines.find(l => l.includes('_0x') && l.includes('hlsPlaylist'));

if (!targetLine) {
    console.log("No hlsPlaylist line found");
    process.exit(1);
}

console.log("Found target line, length:", targetLine.length);

// Strip script tags and trailing HTML
let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

console.log("JS length after cleanup:", js.length);

// Check if it uses document.write or hlsPlaylist
const isDocumentWrite = js.includes("document['write']") || js.includes("document.write");
console.log("Uses document.write:", isDocumentWrite);
console.log("Uses hlsPlaylist:", js.includes("hlsPlaylist"));

// Try to find the array function
const arrayFuncMatch = js.match(/function (_0x[a-f0-9]+)\(\)\{var _0x[a-f0-9]+=\[/);
if (arrayFuncMatch) {
    console.log("Found array function:", arrayFuncMatch[1]);
}

// Try to find the shuffler
const shufflerEnd = js.indexOf('));var ');
if (shufflerEnd > 0) {
    console.log("Found shuffler end at:", shufflerEnd);
}

// Try to find hlsPlaylist
const hslMatch = js.match(/var hlsPlaylist=(_0x[a-f0-9]+)/);
if (hslMatch) {
    console.log("Found hlsPlaylist var:", hslMatch[1]);
}

// Try to find the mainPlayer setup
const mainPlayerIdx = js.indexOf('mainPlayer[');
if (mainPlayerIdx > 0) {
    console.log("Found mainPlayer setup at:", mainPlayerIdx);
    const beforeMainPlayer = js.substring(0, mainPlayerIdx);
    console.log("Code before mainPlayer:", beforeMainPlayer.slice(-200));
}

// The issue is the new format uses _0x14e4 for decoding
// Let's try a different approach - extract all the needed parts

console.log("\n=== Trying to extract components ===");

// Extract the string array function (contains the Base64-like chars)
const stringArrayFunc = js.match(/function _0x51c0\(\)[^}]+\}/);
if (stringArrayFunc) {
    console.log("Found _0x51c0 function");
}

// Extract the _0x14e4 decode function
const decodeFunc = js.match(/function _0x14e4\([^)]+\)[^}]+\}/);
if (decodeFunc) {
    console.log("Found _0x14e4 decode function, length:", decodeFunc[0].length);
}

// Let's look for where the URL is actually built
// Look for patterns that contain 'file' property
const filePropMatch = js.match(/_0x[a-f0-9]+=\{\};.*?'file'[^}]+}/s);
if (filePropMatch) {
    console.log("\nFound file property object");
    console.log("Sample:", filePropMatch[0].slice(0, 300));
}

// Try extracting and evaluating the key parts
// The key is to get the _0x373126 and _0x492df0 functions decoded

// Look at the actual pattern for building URLs
console.log("\n=== Looking at URL building patterns ===");
const urlParts = js.match(/_0x[0-9a-f]+\([^)]+\)\+_0x[0-9a-f]+\([^)]+/g);
if (urlParts) {
    console.log("Found URL concatenation patterns:", urlParts.length);
    console.log("First pattern:", urlParts[0]?.slice(0, 100));
}
