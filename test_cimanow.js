const fs = require('fs');

const html = fs.readFileSync('cimanow_watch.html', 'utf8');

// The script is inside <script type="text/javascript" language="Javascript">
const scriptMatch = html.match(/<script type="text\/javascript" language="Javascript">([\s\S]*?)<\/script>/);

if (!scriptMatch) {
    console.log("Script not found!");
    process.exit(1);
}

let jsCode = scriptMatch[1];

// Polyfill document.open, document.write, document.close
const polyfill = `
var document = {
    written: "",
    open: function() {},
    write: function(str) { this.written += str; },
    close: function() {}
};
`;

jsCode = polyfill + jsCode;

console.log("Evaluating JS of length " + jsCode.length + "...");

try {
    eval(jsCode);
    console.log("Decoded HTML length: " + document.written.length);
    console.log("Decoded HTML snippet: " + document.written.substring(0, 250));
    
    if (document.written.includes('<ul') || document.written.includes('<li')) {
        console.log("SUCCESS! The HTML was perfectly descrambled.");
    } else {
        console.log("FAILED. The HTML does not contain expected tags.");
    }
} catch (e) {
    console.error("Evaluation failed: ", e);
}
