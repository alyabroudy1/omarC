const fs = require('fs');

const files = ['cimanow_watch.html', 'cimanow_watch_302.html'];

files.forEach(file => {
    if (!fs.existsSync(file)) {
        console.log(`${file} does not exist.`);
        return;
    }
    const html = fs.readFileSync(file, 'utf8');
    console.log(`=== Inspecting ${file} (length: ${html.length}) ===`);
    console.log("Contains 'tabcontent':", html.includes('tabcontent'));
    console.log("Contains 'embed':", html.includes('embed'));
    console.log("Contains 'iframe':", html.includes('iframe'));
    console.log("Contains 'btns':", html.includes('btns'));
    console.log("Contains '_169c2':", html.includes('_169c2'));
});
