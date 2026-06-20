const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
let match;
let count = 0;
while ((match = scriptRegex.exec(html)) !== null) {
    console.log(`Script #${count++}:`);
    const code = match[1].trim();
    console.log(code.substring(0, 1000) + (code.length > 1000 ? "\n... [truncated]" : ""));
    console.log("-----------------------------------------");
}
