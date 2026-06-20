const fs = require('fs');
const html = fs.readFileSync('temp_decrypted_full.html', 'utf8');

// Strip all script tags
const noScripts = html.replace(/<script[\s\S]*?<\/script>/gi, '').trim();

// Use regex to find tags
const tags = ['ul', 'li', 'a', 'div', 'section', 'iframe'];
tags.forEach(tag => {
    console.log(`=== Matches of <${tag}> ===`);
    const regex = new RegExp('<' + tag + '[^>]*>([\\s\\S]*?)<\/' + tag + '>', 'gi');
    let match;
    let count = 0;
    while ((match = regex.exec(noScripts)) !== null && count < 15) {
        console.log(`[${count}] <${tag} ...>: ${match[1].trim().substring(0, 300)}`);
        count++;
    }
});
