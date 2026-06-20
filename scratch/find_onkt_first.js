const fs = require('fs');

const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split('\n');
const line2 = lines[1];

const idx = line2.indexOf('onkt');
console.log("First occurrence of onkt at index:", idx);
if (idx !== -1) {
    console.log("Context:", line2.substring(idx - 200, idx + 200));
}
