// Implement proper decoder for the obfuscated URL
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');

// Extract the string table from _0x51c0
const func51c0Match = html.match(/function _0x51c0\(\)[\s\S]*?return _0x51c0\(\);}/);
if (!func51c0Match) {
    console.log("ERROR: No _0x51c0");
    process.exit(1);
}

const rawStrings = func51c0Match[0].match(/'[^']+'/g);
const stringTable = rawStrings.map(s => s.slice(1, -1));
console.log("String table size:", stringTable.length);

// Now let's implement the _0x14e4 decoder based on the function code
// Looking at the decode function, it:
// 1. Takes an index and key
// 2. Gets string from table at index-offset
// 3. Does Base64 decode

// Let me extract the decode function more carefully
const decodeStart = html.indexOf('function _0x14e4');
const decodeEnd = html.indexOf('var _0x443697');
const decodeFunc = html.substring(decodeStart, decodeEnd);

// Find the Base64 alphabet and decode logic
// Looking at the code, it uses atob() with some transformations

// Let's extract the actual values we need for the URL
console.log("\n=== Extracting URL decode calls ===");

// Find where file URL is built
const fileStart = html.indexOf("_0x5e3ffb['file']=");
const fileEnd = html.indexOf("',_0x5e3ffb['image']=");
if (fileStart >= 0 && fileEnd >= 0) {
    const fileSection = html.substring(fileStart, fileEnd);
    console.log("File section length:", fileSection.length);
    
    // Find all _0x373126 and _0x492df0 calls
    const decodeCalls = fileSection.match(/_0x(?:373126|492df0)\([^)]+\)/g);
    console.log("Decode calls:", decodeCalls?.length);
    
    if (decodeCalls && decodeCalls.length > 0) {
        console.log("\nFirst 10 decode calls:");
        decodeCalls.slice(0, 10).forEach((c, i) => console.log(`  ${i}: ${c}`));
    }
}

// Let me try to understand the decode function better
// by looking at what it does with the arguments

// The _0x373126 function does: return _0x14e4(arg1 - 0x2b2, arg2)
// The _0x14e4 function does Base64-like decode

// Looking at the decode function, here's what it does:
// 1. Get string from table at index: _0x40f338[_0x180c93]
// 2. The index is calculated as: _0x180c93 = n - (-0x1*0x1d9e+-0x9e*-0x7+0xce5*0x2)
//    Which is: n - ( -7590 + 686 + 8362) = n - 1458

// Actually let me find the exact offset from the function
console.log("\n=== Analyzing decode offsets ===");

// Find the offset in _0x373126
const offset373126 = fileSection.match(/_0x14e4\(([^,]+),/);
if (offset373126) {
    console.log("_0x373126 offset:", offset373126[1]);
}

// Let me try to implement a working decoder
// by extracting the exact decode logic

// Extract the key parts of _0x14e4
// Looking at: _0x180c93=_0x180c93-(-0x1*0x1d9e+-0x9e*-0x7+0xce5*0x2);
// This equals: _0x180c93 = n - 1458

// The table lookup is: _0x40f338[_0x180c93]
// Where _0x40f338 = _0x51c0() returns the string array

// Then it does Base64 decode on that string

// Let me find what Base64 strings are used
// Looking for atob() calls

// Actually, the simpler approach is to just execute the JS we need
// Let me try creating a minimal working eval

console.log("\n=== Trying minimal eval ===");

// Find the start of the main IIFE
const iifeStart = html.indexOf('(function(_0x');
if (iifeStart >= 0) {
    // Find where hlsPlaylist is assigned
    const hlsAssign = html.indexOf('var hlsPlaylist=');
    if (hlsAssign >= 0) {
        // Extract the code we need
        const codeToEval = html.substring(iifeStart, hlsAssign + 50);
        
        console.log("Code length:", codeToEval.length);
        
        // Try to extract the string table and decode function separately
        // and manually compute
        
        // First, find what indices are used
        // Looking at _0x373126(0x359,0x334,0x390,0x329)
        // This is _0x14e4(0x359-0x2b2, 0x334) = _0x14e4(0x107, 0x334)
        
        // Let me manually decode using the pattern
        // The offset is 0x2b2 = 690
        
        // Extract the index calculations
        const indices = fileSection.match(/_0x(?:373126|492df0)\((0x[0-9a-f]+),/g);
        if (indices) {
            console.log("\nIndices found:", indices.length);
            
            // Parse first few
            const parsed = indices.slice(0, 5).map(s => {
                const match = s.match(/_0x[0-9a-f]+\((0x[0-9a-f]+),/);
                return match ? parseInt(match[1], 16) - 0x2b2 : null;
            });
            console.log("Calculated indices:", parsed);
            
            // Now we need to look up these indices in the string table
            // But the table is also scrambled by the shuffler
        }
    }
}

// Let me try a different approach - look for any hardcoded URLs in the JS
// that might be the actual stream URL after deobfuscation

console.log("\n=== Looking for complete URLs in JS ===");

// Search for patterns that look like they could be URLs
const urlPatterns = [
    /'https?:\/\/[^\']+'/g,
    /"https?:\/\/[^"]+"/g,
];

for (const pattern of urlPatterns) {
    const matches = html.match(pattern);
    if (matches) {
        console.log(`Pattern ${pattern}:`, matches.slice(0, 5));
    }
}

// Let me try to find the actual encoded URL by looking at what gets assigned to 'file'
console.log("\n=== File property assignment ===");

// Find the exact assignment
const filePropMatch = html.match(/_0x5e3ffb\['file'\]='([^']+)'/);
if (filePropMatch) {
    console.log("Direct file:", filePropMatch[1]);
}

// The file is built as: 'https://im' + decoded_part + ...
// Let me extract all the pieces

console.log("\n=== Trying to decode using known patterns ===");

// The domain should be something like: xxx.faselhdx.xyz or xxx.scdns.io
// Let's search for any such patterns in the decoded table

// Try to find common domain parts
const domains = ['scdns', 'fasel', 'video', 'stream', 'cloudfront', 'cdn'];
for (const d of domains) {
    for (let i = 0; i < stringTable.length; i++) {
        if (stringTable[i].toLowerCase().includes(d)) {
            console.log(`Found '${d}' at index ${i}: ${stringTable[i]}`);
        }
    }
}

// Let me try one more thing - extract and run the full decode in a more complete way
console.log("\n=== Last attempt: Full decode ===");

// Since manual decode is complex, let's try extracting and running the specific decode
// We need: the string table getter, the decode function, and the specific call

// Extract _0x51c0 and _0x14e4
const allCode = html.substring(0, html.indexOf("var hlsPlaylist="));

// Try to find what variable holds the final decoded URL
// Look for: 'file': or "file":
const fileKeyMatch = html.match(/_0x5e3ffb\['file'\]=([^;]+);/);
if (fileKeyMatch) {
    console.log("File value expression:", fileKeyMatch[1].slice(0, 200));
}
