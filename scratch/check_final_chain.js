const fs = require('fs');
if (fs.existsSync('cimanow_final_chain.html')) {
    const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
    const regex = /.{0,50}iframe.{0,100}/gi;
    let match;
    let count = 0;
    while ((match = regex.exec(html)) !== null && count < 10) {
        console.log(`Match ${count}: ${match[0].trim()}`);
        count++;
    }
}
