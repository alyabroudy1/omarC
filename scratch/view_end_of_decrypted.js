const fs = require('fs');
const content = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

const lines = content.split('\n');
console.log("Total lines:", lines.length);

const lastLines = lines.slice(Math.max(0, lines.length - 100));
lastLines.forEach((line, idx) => {
    console.log(`${lines.length - 100 + idx}: ${line.trim().substring(0, 150)}`);
});
