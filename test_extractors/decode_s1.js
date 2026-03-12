// Approach: extract the shuffler, string array, and lookup function
// by splitting the JS into known boundaries, skipping anti-debug code
const fs = require('fs');
const html = fs.readFileSync(__dirname + '/../server1.html', 'utf8');
const lines = html.split('\n');
let targetLine = lines[28]; // line 29

// Strip <script> tags and trailing HTML
let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

// Step 1: Find the document['write'](...) call argument
const writeMatch = js.match(/document\['write'\]\(([^;]+)\);$/);
if (!writeMatch) { console.log("No write call found"); process.exit(1); }
const writeArg = writeMatch[1];

// Step 2: Extract the string array function _0x4faf
const pafIdx = js.indexOf('function _0x4faf()');
const pafEnd = js.indexOf('return _0x4faf();}', pafIdx);
const pafFunc = js.substring(pafIdx, pafEnd + 'return _0x4faf();}'.length);

// Step 3: Extract the shuffler IIFE at the beginning
// It's (function(_0x...,_0x...){...}(_0x4faf,...));
const shufflerEnd = js.indexOf('));var _0x3078c9');
const shufflerCode = js.substring(0, shufflerEnd + 2); // include ));

// Step 4: Extract the _0x521e function
const lookupIdx = js.indexOf('function _0x521e(');
// Find the end - it ends with _0x521e(_0x1ddb90,_0x20c806);}
const lookupEndStr = '_0x521e(_0x1ddb90,_0x20c806);}';
const lookupEnd = js.indexOf(lookupEndStr, lookupIdx);
const lookupFunc = js.substring(lookupIdx, lookupEnd + lookupEndStr.length);

// Step 5: Extract the wrapper functions _0x2b98fb and _0x39fc68
const fn1Idx = js.indexOf('function _0x2b98fb(');
const fn1End = js.indexOf('}', fn1Idx + 30);
const fn1Func = js.substring(fn1Idx, fn1End + 1);

const fn2Idx = js.indexOf('function _0x39fc68(');
const fn2End = js.indexOf('}', fn2Idx + 30);
const fn2Func = js.substring(fn2Idx, fn2End + 1);

console.log("=== Extracted components ===");
console.log(`Shuffler: ${shufflerCode.length} chars`);
console.log(`_0x4faf: ${pafFunc.length} chars`);
console.log(`_0x521e: ${lookupFunc.length} chars`);
console.log(`_0x2b98fb: ${fn1Func.length} chars`);
console.log(`_0x39fc68: ${fn2Func.length} chars`);
console.log(`writeArg: ${writeArg.length} chars`);

// Step 6: assemble minimal JS and eval
const minJs = `
${pafFunc}
${shufflerCode}
${lookupFunc}
${fn1Func}
${fn2Func}
var __result = ${writeArg};
__result;
`;

try {
    const result = eval(minJs);
    console.log("\n=== DECODED HTML ===");
    console.log(result);
    
    // Extract URLs
    const m3u8s = result.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
    if (m3u8s) {
        console.log("\n=== M3U8 URLs ===");
        m3u8s.forEach((u, i) => console.log(`${i+1}. ${u}`));
    }
    
    const dataUrls = result.match(/data-url="([^"]+)"/g);
    if (dataUrls) {
        console.log("\n=== data-url attributes ===");
        dataUrls.forEach((u, i) => console.log(`${i+1}. ${u}`));
    }
} catch (e) {
    console.error("Eval error:", e.message);
    // Debug: print first/last chars of each component
    console.log("\nShuffler last 100:", shufflerCode.slice(-100));
    console.log("\n_0x4faf first 100:", pafFunc.slice(0, 100));
    console.log("\n_0x521e first 100:", lookupFunc.slice(0, 100));
    console.log("\n_0x521e last 100:", lookupFunc.slice(-100));
    console.log("\n_0x2b98fb:", fn1Func);
    console.log("\n_0x39fc68:", fn2Func);
}
