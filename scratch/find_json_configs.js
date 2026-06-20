const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    const terms = ['post_id', 'post_link', 'ver', 'player_api', 'ajax'];
    terms.forEach(term => {
        const regex = new RegExp('["\']?' + term + '["\']?\\s*:', 'i');
        const match = html.match(regex);
        if (match) {
            console.log(`=== Found '${term}' ===`);
            console.log(html.substring(match.index - 150, match.index + 500));
        }
    });
}
