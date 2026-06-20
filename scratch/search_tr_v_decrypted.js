const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    const regex = /\/tr\/v\//gi;
    let match;
    let count = 0;
    while ((match = regex.exec(html)) !== null && count < 10) {
        const index = match.index;
        console.log(`Found /tr/v/ at index ${index}:`);
        console.log(html.substring(index - 100, index + 300));
        count++;
    }
}
