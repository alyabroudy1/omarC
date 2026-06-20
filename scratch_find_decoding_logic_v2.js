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

console.log("=== Searching for 151, 152, 153, 149 ===");
findContext('151'); // Index of "a"
findContext('152'); // Index of "b"
findContext('153'); // Index of "c"
findContext('149'); // Index of MD5 constant
