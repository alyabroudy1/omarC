const fs = require('fs');
const html = fs.readFileSync('cimanow_watch.html', 'utf8');

console.log("Length:", html.length);
console.log("Contains 'document.write'?", html.includes('document.write'));

// Find positions of "document.write"
let idx = -1;
while ((idx = html.indexOf('document.write', idx + 1)) !== -1) {
    console.log(`Found 'document.write' at: ${idx}, snippet: ${html.substring(idx - 50, idx + 150)}`);
}

// Find positions of "write"
let idx2 = -1;
let count = 0;
while ((idx2 = html.indexOf('write', idx2 + 1)) !== -1) {
    count++;
    if (count < 10) {
        console.log(`Found 'write' at: ${idx2}, snippet: ${html.substring(idx2 - 20, idx2 + 50)}`);
    }
}
console.log("Total 'write' count:", count);
