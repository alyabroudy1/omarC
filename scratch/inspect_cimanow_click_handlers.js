const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    
    // Extract script contents
    const regex = /<script[\s\S]*?>([\s\S]*?)<\/script>/gi;
    let match;
    let count = 0;
    while ((match = regex.exec(html)) !== null) {
        const js = match[1];
        if (js.includes('data-index') || js.includes('data-id') || js.includes('click') || js.includes('ajax') || js.includes('post')) {
            console.log(`=== Script ${count} contains matching terms (length: ${js.length}) ===`);
            console.log(js.substring(0, 1000));
        }
        count++;
    }
}
