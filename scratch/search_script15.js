const fs = require('fs');

if (fs.existsSync('script15.js')) {
    const js = fs.readFileSync('script15.js', 'utf8');
    console.log("script15.js size:", js.length);
    
    // Find ajax/wp-json/url/post occurrences with context
    const regex = /(?:ajax|wp-json|post|admin-ajax|\$\.post|\$\.ajax)/gi;
    let match;
    let count = 0;
    while ((match = regex.exec(js)) !== null && count < 10) {
        const index = match.index;
        console.log(`Match ${count} [${match[0]}]:`);
        console.log(js.substring(index - 50, index + 200) + "\n");
        count++;
    }
} else {
    console.log("script15.js not found");
}
