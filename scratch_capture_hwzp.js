const fs = require('fs');

// Mock browser globals to prevent crash during execution of decrypted script
global.jQuery = function() { return global.jQuery; };
global.jQuery.ready = function(fn) { fn(); };
global.window = { jQuery: global.jQuery };
global.document = {
    createElement: function() { return { setAttribute: () => {}, appendChild: () => {} }; },
    querySelectorAll: function() { return []; },
    querySelector: function() { return null; }
};
global.escape = encodeURIComponent;

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Inject code to capture HWzp inside EjAx
const target = 'let juCp=eval(HWzp);';
const replacement = 'global.capturedCode = HWzp; let juCp=eval(HWzp);';
const modifiedLine2 = line2.replace(target, replacement);

console.log("Modified line2 successfully? ", modifiedLine2 !== line2);

try {
    eval(modifiedLine2);
    console.log("Decryption and execution finished!");
    if (global.capturedCode) {
        console.log("Captured code size:", global.capturedCode.length);
        fs.writeFileSync('temp_deobfuscated.js', global.capturedCode, 'utf8');
        console.log("Written decrypted code to temp_deobfuscated.js");
    } else {
        console.log("Failed to capture decrypted code");
    }
} catch (e) {
    console.log("Execution finished with an error (expected if browser-only APIs are missing), but checking if capturedCode was populated...");
    if (global.capturedCode) {
        console.log("SUCCESS! Captured code size:", global.capturedCode.length);
        fs.writeFileSync('temp_deobfuscated.js', global.capturedCode, 'utf8');
        console.log("Written decrypted code to temp_deobfuscated.js");
    } else {
        console.error("Failed to capture code. Error:", e);
    }
}
