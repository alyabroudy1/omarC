const fs = require('fs');
const content = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

console.log("Decrypted content total size:", content.length);
const trimmed = content.trim();
console.log("Trimmed size:", trimmed.length);

const lines = content.split('\n');
console.log("Total lines:", lines.length);

let printed = 0;
for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line) {
        console.log(`Line ${i}: ${line.substring(0, 150)}`);
        printed++;
        if (printed > 25) break;
    }
}
