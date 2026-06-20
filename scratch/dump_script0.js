const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
let match;
let count = 0;
while ((match = scriptRegex.exec(html)) !== null) {
    if (count === 0) {
        fs.writeFileSync('cimanow_script0.js', match[1].trim());
        console.log("Saved script 0 to cimanow_script0.js, size:", match[1].trim().length);
    }
    count++;
}
