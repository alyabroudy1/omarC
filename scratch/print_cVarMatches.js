const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_302.html', 'utf8');

const regex = /var (_c\d+)\s*=\s*'([\s\S]*?)';/g;
let match;
let count = 0;
while ((match = regex.exec(html)) !== null) {
    console.log(`Match #${count++}: name=${match[1]} size=${match[2].length}`);
}

// Let's print out the structure of all <script> tags
const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
let sMatch;
let sCount = 0;
while ((sMatch = scriptRegex.exec(html)) !== null) {
    const code = sMatch[1].trim();
    console.log(`Script #${sCount++} attributes:`, sMatch[0].substring(0, sMatch[0].indexOf('>') + 1));
    console.log(`Script #${sCount-1} code snippet:`, code.substring(0, 150) + "...");
}
