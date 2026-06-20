const fs = require('fs');
const html = fs.readFileSync('scratch/cimanow_watch_decrypted_full.html', 'utf8');
console.log("Length:", html.length);
console.log("Snippet:", html.substring(0, 1000));
console.log("Does it contain 'script'?", html.includes('<script'));
console.log("Does it contain 'div'?", html.includes('<div'));
