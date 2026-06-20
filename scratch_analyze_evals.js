const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

let index = 0;
while (true) {
    index = line2.indexOf('eval', index);
    if (index === -1) break;
    console.log(`Found 'eval' at index ${index}:`);
    console.log("Context:", line2.substring(index - 50, index + 150));
    index += 4;
}
