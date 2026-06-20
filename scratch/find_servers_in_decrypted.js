const fs = require('fs');
const content = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

// Print all <li> tags
const liRegex = /<li\b[^>]*>([\s\S]*?)<\/li>/gi;
let match;
let count = 0;
while ((match = liRegex.exec(content)) !== null) {
    console.log(`LI #${count++}:`, match[0].substring(0, 300));
}

// Search for any divs or buttons that might represent the player options
const divRegex = /<div\b[^>]*>/gi;
let divMatch;
const divClasses = new Set();
while ((divMatch = divRegex.exec(content)) !== null) {
    const classAttr = divMatch[0].match(/class="([^"]+)"/);
    if (classAttr) {
        divClasses.add(classAttr[1]);
    }
}
console.log("All div classes:", Array.from(divClasses));

// Search for links (<a> tags) on the page
const aRegex = /<a\b[^>]*>([\s\S]*?)<\/a>/gi;
let aMatch;
let aCount = 0;
while ((aMatch = aRegex.exec(content)) !== null) {
    console.log(`A #${aCount++}:`, aMatch[0].substring(0, 150));
}
