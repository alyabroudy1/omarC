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
    
    console.log("cmYE() =", cmYE());
    console.log("MJXz() =", MJXz());
    console.log("sOgA() + kQiC() =", sOgA() + kQiC());
    console.log("kohz() =", kohz());
    
} catch (e) {
    console.error("Failed:", e);
}
