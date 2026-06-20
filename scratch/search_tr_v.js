const fs = require('fs');

if (fs.existsSync('script15.js')) {
    const js = fs.readFileSync('script15.js', 'utf8');
    const regex = /\/tr\/v\//gi;
    let match;
    while ((match = regex.exec(js)) !== null) {
        const index = match.index;
        console.log(`Found /tr/v/ at index ${index}:`);
        console.log(js.substring(index - 150, index + 350));
    }
} else {
    console.log("script15.js not found");
}
