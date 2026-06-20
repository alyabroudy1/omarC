const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

console.log("Length of line2:", line2.length);
for (let i = 70; i < 110; i++) {
    console.log(`Index ${i}: '${line2[i]}' (code ${line2.charCodeAt(i)})`);
}

// Let's print the end of the line (last 100 characters)
console.log("=== End of line 2 ===");
console.log(line2.substring(line2.length - 100));
for (let i = line2.length - 50; i < line2.length; i++) {
    console.log(`Index ${i}: '${line2[i]}' (code ${line2.charCodeAt(i)})`);
}
