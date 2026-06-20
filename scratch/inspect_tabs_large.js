const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    
    // Find all <ul class="tabcontent"> or similar elements
    const matches = html.match(/<ul[^>]+class=["']tabcontent[^>]*>([\s\S]*?)<\/ul>/gi);
    if (matches) {
        console.log("Found tabcontent elements count:", matches.length);
        matches.forEach((m, idx) => {
            console.log(`=== Tabcontent ${idx} ===`);
            console.log(m.substring(0, 1000));
        });
    } else {
        console.log("No tabcontent elements found with class 'tabcontent'");
    }

    // Let's also print all elements with aria-label="embed"
    const embeds = html.match(/<li[^>]+aria-label=["']embed["'][^>]*>([\s\S]*?)<\/li>/gi);
    if (embeds) {
        console.log("Found embed elements count:", embeds.length);
        embeds.forEach((e, idx) => {
            console.log(`=== Embed ${idx} ===`);
            console.log(e.substring(0, 500));
        });
    }
}
