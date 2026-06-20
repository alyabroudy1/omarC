const fs = require('fs');

const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split('\n');
const line2 = lines[1];

// Find declarations of onkt
const regex = /\b(var|let|const)?\s*onkt\s*=/g;
let match;
while ((match = regex.exec(line2)) !== null) {
    console.log(`Found declaration at index ${match.index}:`);
    console.log(line2.substring(match.index - 50, match.index + 150));
}
