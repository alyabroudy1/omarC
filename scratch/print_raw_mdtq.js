const fs = require('fs');

const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split('\n');
const line2 = lines[1];

const mdTqStart = line2.indexOf('function MdTq');
if (mdTqStart !== -1) {
    const rawMdTq = line2.substring(mdTqStart, mdTqStart + 1200);
    console.log("=== Raw MdTq ===");
    console.log(rawMdTq);
} else {
    console.log("Could not find function MdTq in ajax-5.js");
}
