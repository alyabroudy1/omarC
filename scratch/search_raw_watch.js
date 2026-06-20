const fs = require('fs');
const html = fs.readFileSync('temp_decrypted_full.html', 'utf8');

console.log("Original html length:", html.length);
console.log("Contains 'tabcontent':", html.includes('tabcontent'));
console.log("Contains 'embed':", html.includes('embed'));
console.log("Contains 'watching':", html.includes('watching'));
console.log("Contains 'iframe':", html.includes('iframe'));

// Find all tags
const tags = ['ul', 'li', 'div', 'iframe', 'a'];
tags.forEach(tag => {
    const regex = new RegExp('<' + tag + '[ >]', 'g');
    const matches = html.match(regex);
    console.log(`Tag <${tag}> count:`, matches ? matches.length : 0);
});
