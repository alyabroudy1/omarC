const fs = require('fs');

const html = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

// Print all script tags in decrypted html
const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
let match;
let count = 0;
while ((match = scriptRegex.exec(html)) !== null) {
    console.log(`Script #${count++}:`);
    console.log(match[1].trim().substring(0, 1000) + "...\n");
}

// Print tags with IDs
const idMatches = html.match(/id="([^"]+)"/g);
console.log("All IDs:", idMatches);

// Print classes
const classMatches = html.match(/class="([^"]+)"/g);
const classes = new Set(classMatches ? classMatches.map(c => c.substring(7, c.length-1)) : []);
console.log("All classes:", Array.from(classes));
