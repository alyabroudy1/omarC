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

const lastIndex = code.lastIndexOf('})');
const modifiedCode = code.substring(0, lastIndex) + '; global.sCHB = sCHB; })';

try {
    const fn = eval(modifiedCode);
    fn();
    console.log("Executed successfully!");
    if (global.sCHB) {
        fs.writeFileSync('sCHB_decrypted.json', JSON.stringify(global.sCHB, null, 2), 'utf8');
        console.log("Saved decrypted strings to sCHB_decrypted.json");
    } else {
        console.log("sCHB is still not globally defined");
    }
} catch (e) {
    console.error("Failed:", e);
}
