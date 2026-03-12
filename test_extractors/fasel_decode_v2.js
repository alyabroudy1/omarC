// Try to decode the obfuscated URL
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');

// The URL is built like: 'https://im' + _0x373126(0x335,0x361,0x381,0x31d) + ...

// First, let's extract the string table that _0x14e4 uses
// Find: var _0x56e007=['...','...']
const tableMatch = html.match(/var _0x56e007=\[(.*?)\];/s);
if (!tableMatch) {
    console.log("ERROR: No string table found");
    process.exit(1);
}

// Parse the strings - they're single-quoted
const rawStrings = tableMatch[1].match(/'[^']*'/g);
if (!rawStrings) {
    console.log("ERROR: No strings found in table");
    process.exit(1);
}

// Remove quotes
const strings = rawStrings.map(s => s.slice(1, -1));
console.log("String table size:", strings.length);
console.log("First 10 strings:", strings.slice(0, 10));

// Now implement the _0x14e4 decode function
// It's a Base64-like decoder that takes (index, key) and returns decoded char

// Looking at the function, it does:
// _0x40f338 = _0x51c0() - gets the string array
// _0x180c93 = index - some_offset
// _0x4d2bb0 = _0x40f338[_0x180c93]
// Then does Base64 decode

// Let me extract and implement a minimal version
// The key is: atob with some character transformations

// Let's look at the exact decode logic by finding the Base64 part
const base64Part = html.match(/function _0x14e4.*?return _0x4d2bb0;/s);
if (base64Part) {
    console.log("\nBase64 decode function found, length:", base64Part[0].length);
    
    // Try to find the key lines
    const atobLine = base64Part[0].match(/atob\([^)]+\)/);
    console.log("atob line:", atobLine?.[0]);
}

// Let me try a simpler approach - just decode the known function calls manually
// _0x373126(a,b,c,d) = _0x14e4(a-0x2b2, b)

// Extract the _0x14e4 function to understand it
const func14e4Start = html.indexOf('function _0x14e4');
const func14e4End = html.indexOf('var _0x443697');
if (func14e4Start >= 0 && func14e4End >= 0) {
    const decodeFunc = html.substring(func14e4Start, func14e4End);
    
    // Look for the actual decode logic
    // It's using atob() and decodeURIComponent
    const decodeMatch = decodeFunc.match(/atob\([^)]+\)/g);
    if (decodeMatch) {
        console.log("\nDecode operations:", decodeMatch);
    }
    
    // The function uses a custom transformation
    // Let's look for how it builds the output
    const returnMatch = decodeFunc.match(/return [^;]+;/g);
    if (returnMatch) {
        console.log("\nReturn statements:", returnMatch.slice(0, 3));
    }
}

// The simplest approach: just extract and run the specific decode calls
// For each _0x373126(n1,n2,n3,n4), it returns _0x14e4(n1-0x2b2, n2)

// Let's extract all the decode calls for the URL
const fileSection = html.substring(
    html.indexOf("_0x5e3ffb['file']="),
    html.indexOf("',_0x5e3ffb['image']=")
);

// Extract _0x373126 calls
const decodeCalls = fileSection.match(/_0x373126\((0x[0-9a-f]+,0x[0-9a-f]+,0x[0-9a-f]+,0x[0-9a-f]+)/g);
console.log("\n=== Decode calls ===");
console.log("Count:", decodeCalls?.length);

// Let's manually decode by finding what the _0x14e4 function actually does
// Looking at the function: it's doing Base64 decode with character remapping

// Find the Base64 alphabet it uses
const b64Alphabet = html.match(/'[A-Za-z0-9+\/=]+'/);
if (b64Alphabet) {
    console.log("\nPotential alphabet:", b64Alphabet[0]);
}

// Let me try implementing the decode based on the pattern
// The function _0x14e4(index, key) does:
// 1. Get string at index from table
// 2. Do Base64 decode with key

// Actually, looking more carefully at the code:
// It's using a custom Base64 where it maps characters

console.log("\n=== Trying direct decode ===");

// The key insight: _0x373126 returns a decoded character
// Let's find what _0x373126(0x335,0x361,0x381,0x31d) returns by
// looking at what string would decode to known URL parts

// Try to decode using built-in atob
// The function seems to do: atob with some XOR

// Let me look for the actual transformation
const transformMatch = html.match(/fromCharCode\([^)]+\)/g);
console.log("Transforms:", transformMatch?.slice(0, 5));

// Let me try a different approach - extract the encoded data and try to decode it
// Find where the Base64 string is stored
const encodedMatch = html.match(/atob\("([^"]+)"\)/);
if (encodedMatch) {
    console.log("\nEncoded string found, trying to decode:");
    try {
        const decoded = Buffer.from(encodedMatch[1], 'base64').toString();
        console.log("Decoded:", decoded.slice(0, 100));
    } catch(e) {
        console.log("Decode error:", e.message);
    }
}

// Let's try to find the encoded URL parts differently
// The URL likely starts with: https://<subdomain>.scdns.io or similar

// Search for any part of the URL we can recognize
console.log("\n=== Searching for URL components ===");

// The base URL seems to be: https://<something>.scdns.io or faselhdx.xyz
// Let's look for 'master.m3u8' which should be at the end

// The hlsPlaylist sources array contains: {file: "...master.m3u8", label: "1080p"}
const sourcesMatch = html.match(/_0xa54bc1=\{\}/);
if (sourcesMatch) {
    console.log("Found _0xa54bc1 object");
}

// Let me extract the exact string building and try to understand the decode
// by looking at the function calls

// _0x373126 is a wrapper: _0x14e4(arg1 - 0x2b2, arg2)
// _0x492df0 is similar

// Let's manually compute what the first few characters should be
// For the URL start: 'https://im' + decoded_part
// The decoded part starts after 'im' (which is in the HTML as literal)

// From the output: _0x5e3ffb['file']='https://im'+_0x373126(0x335,0x361,0x381,0x31d)
// So _0x373126(0x335,0x361,0x381,0x31d) should decode to: a-g-a-i-n-.-f-a-s-e-l-h-d-x-.-x-y-z

// Let's implement a minimal decoder based on the pattern
// Looking at the _0x14e4 function, it:
// 1. Uses a character mapping table
// 2. Does Base64-like decode

// Try to extract and run the decode function
console.log("\n=== Implementing decoder ===");

// Find where _0x51c0 returns the string array
const func51c0 = html.match(/function _0x51c0\(\)[\s\S]*?return _0x51c0\(\);}/);
if (func51c0) {
    console.log("Found _0x51c0 function");
    
    // Extract the string array from it
    const innerStrings = func51c0[0].match(/'[^']+'/g);
    console.log("Inner strings:", innerStrings?.slice(0, 10));
}

// The decode is complex. Let me try one more thing:
// Search for any existing m3u8 URLs that might be in the CDN response

console.log("\n=== Last attempt: check if URL is in any HTTP response in the JS ===");
// Search for patterns that look like complete stream URLs
const streamPatterns = html.match(/'https?:\/\/[^']+'/g);
if (streamPatterns) {
    // Filter for likely video URLs
    const videoUrls = streamPatterns.filter(u => 
        u.includes('.m3u8') || 
        u.includes('scdns') || 
        u.includes('video') ||
        u.includes('stream')
    );
    console.log("Potential video URLs:", videoUrls);
}
