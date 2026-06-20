const fs = require('fs');

// Define dummy globals so the script doesn't crash on undefined references
global.jQuery = function() { return global.jQuery; };
global.jQuery.ready = function(fn) { fn(); };
global.window = { jQuery: global.jQuery };
global.document = {};

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Replace "return eval(" with "return ("
const modifiedLine2 = line2.replace('return eval(', 'return (');

try {
    const decryptedCode = eval(modifiedLine2);
    console.log("Decryption successful!");
    console.log("Decrypted code size:", decryptedCode.length);
    fs.writeFileSync('temp_deobfuscated.js', decryptedCode, 'utf8');
    console.log("Written to temp_deobfuscated.js");
} catch (e) {
    console.error("Error decrypting:", e);
}
