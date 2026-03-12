// The decode function is more complex. Let me try extracting and using the actual JS
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// The _0x14e4 function is a custom Base64 decoder
// Let me extract the exact function and implement it

// Find the function
const decodeStart = html.indexOf('function _0x14e4');
const decodeEnd = html.indexOf('var _0x443697');
const decodeFunc = html.substring(decodeStart, decodeEnd);

// Find the string table getter _0x51c0
const func51c0 = html.match(/function _0x51c0\(\)[\s\S]*?return _0x51c0\(\);}/);
if (!func51c0) {
    console.log("ERROR: No _0x51c0");
    process.exit(1);
}

// Get the string table
const rawStrings = func51c0[0].match(/'[^']+'/g);
const stringTable = rawStrings.map(s => s.slice(1, -1));

// Now let's look at the decode function implementation
// The key part is understanding what _0x14e4(index, key) returns

// Looking at the decode function:
// It has an inner function that does Base64 decode
// Let's find it
const innerFunc = decodeFunc.match(/var _0x2c7b0d=function\([^)]+\)[^{]+\{[^}]+\}/);
if (innerFunc) {
    console.log("Inner function found");
    console.log(innerFunc[0].slice(0, 300));
}

// Let me try a simpler approach - just evaluate the JS we need
// Extract just the decode call we need

console.log("\n=== Extracting decode calls for URL ===");

// Find the section that builds the file URL
const fileStart = html.indexOf("_0x5e3ffb['file']='https://im'");
if (fileStart >= 0) {
    const fileSection = html.substring(fileStart, fileStart + 500);
    
    // Get all decode calls
    const calls = fileSection.match(/_0x(?:373126|492df0)\([^)]+\)/g);
    console.log("Found", calls?.length, "decode calls");
    
    if (calls) {
        // Show first few
        console.log("\nFirst 5 calls:");
        calls.slice(0, 5).forEach((c, i) => console.log(`  ${i}: ${c}`));
    }
}

// Let me try a different approach - directly evaluate the specific calls
// by extracting the relevant functions

// First, let's understand what _0x373126 does:
// It's: return _0x14e4(_0x3d8808 - 0x2b2, _0x2ecf0f)
// which means: _0x14e4(arg1 - 690, arg2)

// And _0x14e4 does Base64-like decode with the string table

// Let me implement the _0x14e4 function based on what I can see
console.log("\n=== Implementing decoder ===");

// Looking at the decode function more closely:
// It's using _0x56e007 (the string table) with some transformation

// Find the Base64 operations
// Looking at the code, it:
// 1. Gets string from table at index 
// 2. Uses atob() to decode
// 3. Does character remapping

// The key: the function uses _0x51c0() which returns the shuffled string array
// And the index is calculated as: n - some_offset

// Let me extract the exact offset and try to decode
// Looking at _0x373126: _0x14e4(_0x3d8808-0x2b2, _0x2ecf0f)
// For _0x373126(0x335, 0x361, 0x381, 0x31d):
// - First arg: 0x335 - 0x2b2 = 0x83 = 131

// Now let's look at what _0x14e4 does with index 0x83 and key 0x361
// It uses the string table and XORs with the key

// Looking at the code: the key is used to XOR each character
// The transformation: decoded[i] = encoded[i] XOR key[i % key.length]

// The key seems to be: 0x361 = 865 in decimal

// Let me try to decode using the string table and the XOR pattern
// Extract the string table properly - it's in _0x56e007

// Actually, looking at the decode function again:
// It's a complex multi-layer decode. Let me try running the actual JS

console.log("\n=== Running actual decode ===");

// Try to extract and eval the decode function
// We need: _0x51c0 (string table), and _0x14e4 (decode function)

// Extract _0x51c0
const tableFunc = `
function _0x51c0(){
    var _0x56e007 = ${JSON.stringify(stringTable)};
    return _0x56e007;
}
`;

// Now let's try to run the decode
// First, let me look at the actual decode parameters in use

// Get all unique indices used in the URL decode
const fileBuild = html.substring(
    html.indexOf("_0x5e3ffb['file']="),
    html.indexOf("',_0x5e3ffb['image']=")
);

// Extract indices: look for patterns like _0x373126(0x335,...)
const indices = fileBuild.match(/_0x(?:373126|492df0)\((0x[0-9a-f]+),/g);
if (indices) {
    console.log("Indices used:", indices.slice(0, 10));
}

// The decode is too complex for manual implementation
// Let me try a different approach: extract the needed functions and eval

// Try extracting and running just enough to decode the URL
const minimalDecoder = `
${tableFunc}

// The decode function _0x14e4
// Extract from the HTML - it's complex

// Let me find what values we need to decode
console.log("\\n=== Looking at actual decode needed ===");
const neededCalls = fileBuild.match(/_0x(?:373126|492df0)\([^)]+\)/g);
if (neededCalls) {
    console.log("Need to decode", neededCalls.length, "calls");
    
    // For first call: _0x373126(0x335,0x361,0x381,0x31d)
    // = _0x14e4(0x335-0x2b2, 0x361) = _0x14e4(0x83, 0x361)
    
    // The index calculation is: arg1 - 0x2b2
    // Let's find what index 0x83 maps to in the string table
    const idx = 0x83 - (0x2b2);
    console.log("Index would be:", idx);
}

// Let me try running the actual HTML JS in a limited way
console.log("\\n=== Last attempt: eval the full decode ===");

// Find where the playlist object is defined and try to get it
const playlistObj = html.match(/var hlsPlaylist=(_0x[0-9a-f]+),/);
if (playlistObj) {
    console.log("Playlist var:", playlistObj[1]);
}

// Let's try a different approach - just search for any hardcoded URLs in the JS that might work
// Look for any response from CDN

console.log("\\n=== Looking for response URLs ===");
// Try fetching the page again and see if URLs are in any network responses

// For now, let me try to find any complete URLs in the JS
const allUrls = html.match(/https?:\\/\\/[^\\s"'<>]+\\.[a-z]{2,}[^"'<>]*/gi);
if (allUrls) {
    console.log("Found", allUrls.length, "URLs");
    // Filter for video-related
    const videoUrls = allUrls.filter(u => 
        u.includes('.m3u8') || 
        u.includes('video') ||
        u.includes('stream') ||
        u.includes('cdn')
    );
    console.log("Video URLs:", videoUrls);
}
