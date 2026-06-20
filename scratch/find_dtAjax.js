const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    
    // Search for dtAjax or any script content defining keys like site_url or player_api
    const regex = /(?:dtAjax|lalaplayer|url_list|play_method)\s*=/i;
    const match = html.match(regex);
    if (match) {
        console.log("Found match!");
        const index = match.index;
        console.log(html.substring(index - 200, index + 800));
    } else {
        console.log("Not found.");
    }
}
