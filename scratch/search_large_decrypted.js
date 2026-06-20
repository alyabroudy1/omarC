const fs = require('fs');

if (fs.existsSync('cimanow_watch_decrypted_full.html')) {
    const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
    console.log("File size:", html.length);
    console.log("Contains 'tabcontent':", html.includes('tabcontent'));
    console.log("Contains 'embed':", html.includes('embed'));
    console.log("Contains 'btns':", html.includes('btns'));
    console.log("Contains 'iframe':", html.includes('iframe'));
    
    // Print all matches of iframes
    const iframes = html.match(/<iframe[^>]*>/gi);
    if (iframes) {
        console.log("Iframes count:", iframes.length);
        console.log("Iframes:", iframes);
    }
    
    // Print first 500 chars of ul.btns if present
    const btns = html.match(/<ul class=["']btns["']>([\s\S]*?)<\/ul>/i);
    if (btns) {
        console.log("Buttons list HTML snippet:", btns[0].substring(0, 1000));
    }
}
