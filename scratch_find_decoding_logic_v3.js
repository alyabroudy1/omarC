const fs = require('fs');

const code = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

function findContext(keyword) {
    let index = 0;
    while (true) {
        index = code.indexOf(keyword, index);
        if (index === -1) break;
        console.log(`Found '${keyword}' at index ${index}:`);
        console.log("Context:", code.substring(index - 100, index + 350));
        index += keyword.length;
    }
}

console.log("=== Searching for 152, 153, 154 ===");
findContext('152'); // Index of "a" in JSON
findContext('153'); // Index of "b" in JSON
findContext('154'); // Index of "c" in JSON
