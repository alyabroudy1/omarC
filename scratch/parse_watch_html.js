const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_302.html', 'utf8');

console.log("HTML length:", html.length);

const cVarMatch = html.match(/var (_c\d+)\s*=\s*'([\s\S]*?)';/);
if (cVarMatch) {
    console.log("Found cVarMatch variable name:", cVarMatch[1]);
    console.log("Value prefix:", cVarMatch[2].substring(0, 100));
} else {
    console.log("No cVarMatch found.");
}

const rMatch = html.match(/var _r\s*=\s*([0-9\s+]+);/);
if (rMatch) {
    console.log("Found rMatch:", rMatch[0]);
} else {
    console.log("No rMatch found.");
}

// Let's also search for any other inline scripts
const scriptCount = (html.match(/<script/g) || []).length;
console.log("Number of script tags:", scriptCount);

// Let's write all inline scripts to a file
const scripts = [];
const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
let match;
while ((match = scriptRegex.exec(html)) !== null) {
    const code = match[1].trim();
    if (code) {
        scripts.push(code);
    }
}
fs.writeFileSync('cimanow_watch_scripts.txt', scripts.join("\n\n=== SCRIPT ===\n\n"));
console.log(`Saved ${scripts.length} non-empty scripts to cimanow_watch_scripts.txt`);
