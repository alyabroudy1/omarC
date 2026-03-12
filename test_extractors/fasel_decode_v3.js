// Proper decoder implementation
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// Extract the string array from _0x51c0 function
const func51c0 = html.match(/function _0x51c0\(\)[\s\S]*?return _0x51c0\(\);}/);
if (!func51c0) {
    console.log("ERROR: No _0x51c0 function");
    process.exit(1);
}

// Extract string table
const rawStrings = func51c0[0].match(/'[^']+'/g);
const stringTable = rawStrings.map(s => s.slice(1, -1));
console.log("String table size:", stringTable.length);

// Now implement the _0x14e4 decoder
// Looking at the function:
// _0x14e4(index, key) = decoded character
// The function uses the string table and does Base64-like decoding

// Let me extract the decode function to understand it better
const decodeStart = html.indexOf('function _0x14e4');
const decodeEnd = html.indexOf('var _0x443697');
const decodeFunc = html.substring(decodeStart, decodeEnd);

// Find the inner _0x2c7b0d function which does the actual decoding
// It uses: atob with some transformations
// Let me look for the Base64 operations

// Looking at the code: 
// The decode uses: decodeURIComponent(atob(...)) with character remapping
// The key is the string from the table

// Let's look for the exact decode logic
console.log("\n=== Analyzing decode function ===");

// Find the variable that holds the encoded string
const keyVar = decodeFunc.match(/var _0x2bb80d='([^']+)'/);
if (keyVar) {
    console.log("Found encoded key:", keyVar[1]);
    // Decode it - it's Base64
    try {
        const decoded = Buffer.from(keyVar[1], 'base64').toString();
        console.log("Decoded key:", decoded);
    } catch(e) {
        console.log("Decode error:", e.message);
    }
}

// The decode function uses: 
// - The string table _0x56e007 
// - A key that's Base64 encoded

// Looking at the transforms:
// fromCharCode((w-65+26-12)%26+65) for uppercase (A-Z -> shift by 14)
// fromCharCode((w-97+26-12)%26+97) for lowercase (a-z -> shift by 14)  
// fromCharCode(32+(r+n)%95) for other chars

// This is a Caesar cipher with shift 14!

// Let me implement a simpler approach: 
// Extract the exact decode function and run it

console.log("\n=== Trying to decode URL ===");

// Find the URL building section
const fileStart = html.indexOf("_0x5e3ffb['file']='https://im'");
if (fileStart >= 0) {
    const fileSection = html.substring(fileStart, fileStart + 400);
    console.log("File section:", fileSection.slice(0, 300));
    
    // Extract all _0x373126 calls
    const calls = fileSection.match(/_0x373126\([^)]+\)/g);
    console.log("\nDecode calls:", calls?.length);
    
    if (calls) {
        console.log("First 10 calls:");
        calls.slice(0, 10).forEach((c, i) => console.log(`  ${i}: ${c}`));
    }
}

// Let me try implementing the decoder based on the pattern
// _0x373126(a,b,c,d) = _0x14e4(a - 0x2b2, b)

// Extract _0x14e4 function
const fullDecodeFunc = html.substring(
    html.indexOf('function _0x14e4'),
    html.indexOf('var _0x443697')
);

// Find the Base64 alphabet it uses
// Looking at: 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='
const b64Match = fullDecodeFunc.match(/'[A-Za-z0-9+\/=]{64}'/);
console.log("\nBase64 alphabet:", b64Match?.[0]);

// The decode function transforms the string using the key
// Let me try to manually decode by understanding the algorithm

// Looking at the code more carefully:
// - It takes a Base64 string from the table
// - Uses a key for XOR/transformation  
// - Returns decoded string

// Let me try extracting and running a minimal version
console.log("\n=== Building minimal decoder ===");

// Find what _0x373126(0x335, 0x361, 0x381, 0x31d) should return
// This is: _0x14e4(0x335 - 0x2b2, 0x361) = _0x14e4(0x83, 0x361)

// Let me find what the actual decode returns by looking at what values are used
// Search for the key 0x361 in the decode context
const key361 = fullDecodeFunc.match(/0x361/);
console.log("0x361 found:", key361?.length);

// Actually, let's just implement the Caesar cipher decode (shift 14)
// Based on the transforms we saw

function decodeCaesar(str, shift) {
    return str.split('').map(c => {
        if (c >= 'A' && c <= 'Z') {
            return String.fromCharCode(((c.charCodeAt(0) - 65 - shift + 26) % 26) + 65);
        } else if (c >= 'a' && c <= 'z') {
            return String.fromCharCode(((c.charCodeAt(0) - 97 - shift + 26) % 26) + 97);
        } else {
            return c;
        }
    }).join('');
}

// The string table appears to be Caesar cipher encoded
console.log("\n=== Decoding string table with shift 14 ===");
const decodedTable = stringTable.map(s => decodeCaesar(s, 14));
console.log("First 10 decoded:", decodedTable.slice(0, 10));

// Now let's find the encoded URL parts and decode them
// The URL starts with: https://im + decoded parts

// Find what encoded string maps to "faselhdx.xyz"
// by searching our decoded table
console.log("\n=== Looking for URL parts ===");
const searchStr = "faselhdx";
for (let i = 0; i < stringTable.length; i++) {
    if (decodedTable[i].includes(searchStr) || decodedTable[i].includes('fasel')) {
        console.log(`Index ${i}: ${stringTable[i]} -> ${decodedTable[i]}`);
    }
}

// Now let's decode the actual URL
// The URL building: _0x373126(n1,n2,n3,n4) = decode(table[n1-0x2b2], n2)

console.log("\n=== Decoding URL ===");

// Looking at the call: _0x373126(0x335, 0x361, 0x381, 0x31d)
// This is: _0x14e4(0x335-0x2b2, 0x361) = _0x14e4(0x83, 0x361)

// The index calculation: n - 0x2b2 (690)
// The key is used for XOR

// Looking at the decode function: it uses XOR with the key
// Let me look for the key

// Find the XOR key in the decode function
const xorKeyMatch = fullDecodeFunc.match(/0x[0-9a-f]+/g);
console.log("XOR keys found:", xorKeyMatch?.slice(0, 20));

// This is getting complex. Let me try a different approach:
// Just execute the exact needed JS using Function constructor

// Extract just the parts needed to decode the file URL
const extractForUrl = () => {
    // Find the section that builds the file URL
    const fileBuild = html.match(/_0x5e3ffb\['file'\]='https:\/\/im'\+([^;]+);/);
    if (fileBuild) {
        console.log("File build parts:", fileBuild[1].slice(0, 200));
    }
};

console.log("\n=== Direct extraction attempt ===");

// Let me look for any HTTP requests in the JS that might give us a hint
// Search for the CDN domain patterns
const cdnPatterns = [
    'scdns',
    'faselhdx',
    'video',
    'stream',
    'cloudfront'
];

for (const p of cdnPatterns) {
    const idx = decodedTable.indexOf(p);
    if (idx >= 0) {
        console.log(`Found '${p}' at index ${idx}, encoded: ${stringTable[idx]}`);
    }
}

// Let me try to find the URL by searching in all decoded strings
console.log("\n=== All decoded strings containing relevant patterns ===");
for (let i = 0; i < decodedTable.length; i++) {
    if (decodedTable[i].includes('.') && decodedTable[i].length > 5) {
        console.log(`${i}: ${decodedTable[i]}`);
    }
}
