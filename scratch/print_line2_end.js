const fs = require('fs');

const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split('\n');
const line2 = lines[1];

console.log("=== End of Line 2 ===");
console.log(line2.substring(line2.length - 200));
