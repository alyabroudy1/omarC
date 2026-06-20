const fs = require('fs');

// Read the second line of ajax-5.js
const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
console.log("Number of lines:", lines.length);
const line2 = lines[1];
console.log("Line 2 length:", line2.length);

// We want to override eval so that when line 2 executes it, we capture the string.
let capturedCode = '';
const originalEval = eval;
global.eval = function(code) {
    capturedCode = code;
    return originalEval(code);
};

// Now, run the line 2 code in our context.
// Wait, the code has: "let k53x;!function(){...}"
// We can just run it using standard eval or vm module.
try {
    // We execute the line 2 string.
    originalEval(line2);
    console.log("Successfully executed line 2!");
    fs.writeFileSync('temp_deobfuscated.js', capturedCode, 'utf8');
    console.log("Captured code size:", capturedCode.length);
} catch (e) {
    console.error("Error executing line 2:", e);
}
