const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_302.html', 'utf8');

// Find script tags and display the one that decrypts
const match = html.match(/<script type="text\/javascript" language="Javascript">([\s\S]*?)<\/script>/);
if (match) {
    const script = match[1];
    console.log("Script length:", script.length);
    // Print the end of the script where the evaluation or document.write happens
    console.log("End of script (last 1500 chars):");
    console.log(script.substring(script.length - 1500));
} else {
    console.log("No matching script found.");
}
