const fs = require('fs');
const js = fs.readFileSync('cimanow_script.js', 'utf8');
fs.writeFileSync('cimanow_script_head.js', js.substring(0, 15000));
console.log("Written head of script.");
