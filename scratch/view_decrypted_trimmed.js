const fs = require('fs');
const html = fs.readFileSync('scratch/cimanow_watch_decrypted_full.html', 'utf8');
const trimmed = html.trim();
console.log("Trimmed Length:", trimmed.length);
console.log("Trimmed Snippet:");
console.log(trimmed.substring(0, 1000));
console.log("Ends with:");
console.log(trimmed.substring(trimmed.length - 1000));
