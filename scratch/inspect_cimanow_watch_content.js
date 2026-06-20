const fs = require('fs');
if (fs.existsSync('cimanow_watch.html')) {
    const html = fs.readFileSync('cimanow_watch.html', 'utf8');
    console.log("Snippet:", html.substring(0, 500));
}
