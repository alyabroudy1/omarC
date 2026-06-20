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

const cleanDecrypterCode = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');
let cleanCode = cleanDecrypterCode.trim();
if (cleanCode.startsWith('(function(){')) cleanCode = cleanCode.substring(12);
if (cleanCode.endsWith('})()')) cleanCode = cleanCode.substring(0, cleanCode.length - 4);
if (cleanCode.endsWith('})')) cleanCode = cleanCode.substring(0, cleanCode.length - 2);

// Evaluate cleanCode to initialize k53x and sCHB
eval(cleanCode);

const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split('\n');
const line2 = lines[1];

// Extract MdTq definition: starts with "function MdTq(" and ends after its closing brace.
// Let's find index of "function MdTq("
const mdTqStart = line2.indexOf('function MdTq');
// It ends with some braces. Let's extract 1200 characters from mdTqStart and deobfuscate them.
const rawMdTq = line2.substring(mdTqStart, mdTqStart + 1200);

// Extract IOns block:
const ionsStart = line2.indexOf('var gNks=k53x.w42t(149),IOns=');
// It goes up to the return of IOns. Let's extract 1200 characters from ionsStart.
const rawIons = line2.substring(ionsStart - 100, ionsStart + 1200);

// Helper to deobfuscate using regex matching of k53x calls
// E.g., k53x.UCBv(33) or k53x[k53x.UCBv(33)]() or k53x.UCBv(index)
// Let's replace any k53x.method(index) with the decrypted string or index evaluation
function deobfuscateCode(rawJs) {
    let cleanJs = rawJs;
    
    // Replace math expressions like (0O57060516-0xbc614d) with their actual values
    cleanJs = cleanJs.replace(/\(0O57060516-0xbc614d\)/g, '1');
    cleanJs = cleanJs.replace(/\(0x75bcd15-0O726746425\)/g, '0');
    cleanJs = cleanJs.replace(/\(0O334664274-0x37368B9\)/g, '1');
    cleanJs = cleanJs.replace(/\(15658734\^0O73567354\)/g, '2');

    // Replace k53x calls like k53x.waEv(152) or k53x[k53x.oUSt(150)]
    // We can evaluate k53x[method](num) using a regex
    const k53xCallRegex = /k53x\.([a-zA-Z0-9]+)\((\d+)\)/g;
    cleanJs = cleanJs.replace(k53xCallRegex, (match, method, numStr) => {
        const num = parseInt(numStr);
        if (typeof k53x[method] === 'function') {
            const val = k53x[method](num);
            return JSON.stringify(val);
        }
        return match;
    });

    // Also replace k53x[k53x.method(num)] or similar nested calls
    // E.g., k53x[k53x.oUSt(150)]
    const nestedCallRegex = /k53x\["([a-zA-Z0-9]+)"\]/g;
    cleanJs = cleanJs.replace(nestedCallRegex, (match, key) => {
        if (typeof k53x[key] === 'function') {
            const val = k53x[key]();
            if (typeof val === 'function') {
                return `/* ${key} -> ${val.name} */`;
            }
            return JSON.stringify(val);
        }
        return match;
    });

    return cleanJs;
}

console.log("=== DEOBFUSCATING MdTq ===");
const cleanMdTq = deobfuscateCode(rawMdTq);
console.log(cleanMdTq.substring(0, 1000));

console.log("\n=== DEOBFUSCATING IOns ===");
const cleanIons = deobfuscateCode(rawIons);
console.log(cleanIons.substring(0, 1000));

fs.writeFileSync('temp_clean_decrypters.js', `// MdTq:\n${cleanMdTq}\n\n// IOns:\n${cleanIons}`);
