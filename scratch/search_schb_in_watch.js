const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    const terms = ['sCHB', 'src=', 'script'];
    terms.forEach(term => {
        const regex = new RegExp(term, 'gi');
        let count = 0;
        let match;
        console.log(`=== Matches for '${term}' ===`);
        while ((match = regex.exec(html)) !== null && count < 10) {
            const index = match.index;
            console.log(html.substring(index - 50, index + 150).trim() + "\n");
            count++;
        }
    });
}
