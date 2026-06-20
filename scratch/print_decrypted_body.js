const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_decrypted_test.html', 'utf8');

const bodyMatch = html.match(/<body\b[^>]*>([\s\S]*?)<\/body>/i);
if (bodyMatch) {
    const bodyContent = bodyMatch[1].trim();
    console.log("Body length:", bodyContent.length);
    console.log("Body preview (first 1000 chars):\n", bodyContent.substring(0, 1000));
    console.log("Body preview (last 1000 chars):\n", bodyContent.substring(bodyContent.length - 1000));
} else {
    console.log("No <body> tag found");
}
