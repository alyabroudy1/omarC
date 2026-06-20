const fs = require('fs');

// Mock browser globals
global.jQuery = function() { return global.jQuery; };
global.jQuery.ready = function(fn) { fn(); };
global.window = { jQuery: global.jQuery };
global.document = {
    createElement: function() { return { setAttribute: () => {}, appendChild: () => {} }; },
    querySelectorAll: function() { return []; },
    querySelector: function() { return null; }
};
global.escape = encodeURIComponent;

const code = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

// Append `; global.sCHB = sCHB;` right before the end `})`
const lastIndex = code.lastIndexOf('})');
const modifiedCode = code.substring(0, lastIndex) + '; global.sCHB = sCHB; })';

try {
    const fn = eval(modifiedCode);
    fn();
    console.log("Executed successfully!");
    if (global.sCHB) {
        console.log("=== Decrypted sCHB Array ===");
        global.sCHB.forEach((str, i) => {
            console.log(`Index ${i}: "${str}"`);
        });
    } else {
        console.log("sCHB is still not globally defined");
    }
} catch (e) {
    console.error("Failed:", e);
}
