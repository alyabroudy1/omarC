const fs = require('fs');

if (fs.existsSync('script15.js')) {
    const js = fs.readFileSync('script15.js', 'utf8');
    const terms = ['player_api', 'play_method', 'lalaplayer', 'data-index', 'wp-json'];
    terms.forEach(term => {
        const regex = new RegExp(term, 'gi');
        let count = 0;
        let match;
        console.log(`=== Matches for '${term}' ===`);
        while ((match = regex.exec(js)) !== null && count < 5) {
            const index = match.index;
            console.log(js.substring(index - 100, index + 200) + "\n");
            count++;
        }
    });
} else {
    console.log("script15.js not found");
}
