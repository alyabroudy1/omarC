const fs = require('fs');

const code = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

// Find all occurrences of '.a', '["a"]', "['a']" or similar
// Also let's find references to the decrypted strings in sCHB
// Let's print out code snippets around references to:
// - sCHB[149] ("9b09102b216d23cbb6cf75b47c82961c")
// - sCHB[151] ("a")
// - sCHB[152] ("b")
// - sCHB[153] ("c")

function findContext(keyword) {
    let index = 0;
    while (true) {
        index = code.indexOf(keyword, index);
        if (index === -1) break;
        console.log(`Found '${keyword}' at index ${index}:`);
        console.log("Context:", code.substring(index - 100, index + 300));
        index += keyword.length;
    }
}

console.log("=== Searching for 'a', 'b', 'c' accessors ===");
findContext('sCHB[151]'); // "a"
findContext('sCHB[152]'); // "b"
findContext('sCHB[153]'); // "c"
findContext('sCHB[149]'); // rand md5
