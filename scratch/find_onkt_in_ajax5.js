const fs = require('fs');

const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split('\n');
const line2 = lines[1];

let index = 0;
while (true) {
    index = line2.indexOf('onkt', index);
    if (index === -1) break;
    console.log(`Found 'onkt' at index ${index}:`);
    console.log("Context:", line2.substring(index - 100, index + 200));
    index += 4;
}
