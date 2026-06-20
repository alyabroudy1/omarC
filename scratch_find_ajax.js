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

console.log("=== Searching for ajax related terms ===");
findContext('onkt');
findContext('$.ajax');
findContext('jQuery');
findContext('ajax');
findContext('31'); // Let's check where 31 appears
