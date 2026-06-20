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

try {
    // Evaluate the function expression
    const fn = eval(code);
    console.log("Evaluated function expression successfully!");
    
    // Execute it
    fn();
    console.log("Executed function successfully!");
    
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
    if (global.sCHB) {
        console.log("=== Decrypted sCHB Array (partial) ===");
        global.sCHB.forEach((str, i) => {
            console.log(`Index ${i}: "${str}"`);
        });
    }
}
