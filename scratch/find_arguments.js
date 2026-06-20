const fs = require('fs');
const code = fs.readFileSync('c:\\Users\\alyab\\AndroidStudioProjects\\cloudstream-standard-v2\\omarC\\temp_deobfuscated_clean.js', 'utf8');

let index = 0;
while (true) {
    index = code.indexOf('arguments', index);
    if (index === -1) break;
    console.log(`Found 'arguments' at index ${index}:`);
    console.log("Context:", code.substring(index - 100, index + 200));
    index += 9;
}
