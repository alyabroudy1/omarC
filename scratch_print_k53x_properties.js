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

let cleanCode = code.trim();
if (cleanCode.startsWith('(function(){')) {
    cleanCode = cleanCode.substring(12);
}
if (cleanCode.endsWith('})()')) {
    cleanCode = cleanCode.substring(0, cleanCode.length - 4);
}
if (cleanCode.endsWith('})')) {
    cleanCode = cleanCode.substring(0, cleanCode.length - 2);
}

try {
    eval(cleanCode);
    console.log("Evaluation successful!");
    
    console.log("=== k53x properties ===");
    for (let key in k53x) {
        console.log(`k53x['${key}'] -> function: ${k53x[key].name || k53x[key].toString().substring(0, 100)}`);
    }
    
} catch (e) {
    console.error("Failed:", e);
}
