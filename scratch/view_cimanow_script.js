const fs = require('fs');
const code = fs.readFileSync('cimanow_script0.js', 'utf8');

// Find the string array definition
// var _0=['\x65\x7a...', ...]
const arrayMatch = code.match(/var _0\s*=\s*\[([\s\S]*?)\];/);
if (arrayMatch) {
    const rawArray = arrayMatch[1];
    // Parse the array
    const strings = eval(`[${rawArray}]`);
    console.log(`Decoded string array of length ${strings.length}:`);
    for (let i = 0; i < Math.min(strings.length, 100); i++) {
        console.log(`[${i}]: ${strings[i]}`);
    }
    fs.writeFileSync('cimanow_decoded_strings.txt', JSON.stringify(strings, null, 2));
} else {
    console.log("String array not found");
}
