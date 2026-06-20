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

// Load the clean decrypted code
const code = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

// Wrap it in a way that we can inspect the global variables inside it
// Since it is an IIFE (function(){...})(), it doesn't expose variables to the global scope directly if they are declared with `function` or `var` inside the IIFE.
// Let's modify the code slightly: replace the outer (function(){ at start and })() at end with nothing, so variables are declared in our script's scope!
let cleanCode = code.trim();
if (cleanCode.startsWith('(function(){')) {
    cleanCode = cleanCode.substring(12);
}
if (cleanCode.endsWith('})()')) {
    cleanCode = cleanCode.substring(0, cleanCode.length - 4);
}

try {
    eval(cleanCode);
    console.log("Execution successful!");
    
    // Print sCHB
    if (global.sCHB) {
        console.log("=== Decrypted sCHB Array ===");
        global.sCHB.forEach((str, i) => {
            console.log(`Index ${i}: "${str}"`);
        });
    } else {
        console.log("sCHB is not defined globally");
    }
} catch (e) {
    console.error("Execution failed:", e);
    // If it failed after EfVs ran, sCHB might still be populated!
    if (global.sCHB) {
        console.log("=== Decrypted sCHB Array (partial) ===");
        global.sCHB.forEach((str, i) => {
            console.log(`Index ${i}: "${str}"`);
        });
    }
}
