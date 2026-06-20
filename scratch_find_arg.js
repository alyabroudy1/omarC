const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Find index of 'G%09%11%05%0B'
const idx = line2.indexOf('G%09%11%05%0B');
console.log("Found payload at index:", idx);
if (idx !== -1) {
    console.log("Characters before payload:", line2.substring(idx - 20, idx));
    console.log("Characters after payload:", line2.substring(idx + 13, idx + 100));
}
