// Decode server2.html using the same static analysis approach
const fs = require('fs');
const html = fs.readFileSync(__dirname + '/../server2.html', 'utf8');
const lines = html.split('\n');

// Find the line with document[
let targetLine = null;
let lineNum = -1;
for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes("document[") && lines[i].includes("_0x")) {
        targetLine = lines[i];
        lineNum = i + 1;
        break;
    }
}

if (!targetLine) { console.log("No obfuscated JS found in server2.html"); process.exit(1); }
console.log(`Found on line ${lineNum}, length: ${targetLine.length}`);

// Strip tags and trailing HTML
let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

// Find document['write'] call
const writeMatch = js.match(/document\['write'\]\(([^;]+)\);$/);
if (!writeMatch) { console.log("No document.write found"); process.exit(1); }
const writeArg = writeMatch[1];

// Extract the function names used in the write argument
const funcNames = [...new Set(writeArg.match(/_0x\w+(?=\()/g))];
console.log("Functions used in write arg:", funcNames);

// Find the string array function (pattern: function _0xNAME(){var ...=[...];...)
const arrayFuncMatch = js.match(/function (_0x\w+)\(\)\{var _0x\w+=\[/);
const arrayFuncName = arrayFuncMatch ? arrayFuncMatch[1] : null;
console.log("String array function:", arrayFuncName);

// Find the lookup function (calls the array function)
// Pattern: function _0xNAME(_0x...,_0x...){var ...=ARRAYFUNC();return _0xNAME=function(...)
const lookupMatch = js.match(/function (_0x\w+)\(_0x\w+,_0x\w+\)\{var _0x\w+=ARRAYFUNC/);

// Actually, let's do it by index like server1
// Find _0x4faf equivalent
const arrayIdx = js.indexOf(`function ${arrayFuncName}()`);
const arrayEnd = js.indexOf(`return ${arrayFuncName}();}`, arrayIdx);
const arrayFunc = js.substring(arrayIdx, arrayEnd + `return ${arrayFuncName}();}`.length);

// Find shuffler IIFE at the beginning  
// Look for the pattern (function(_0x...,_0x...){...}(_0xArrayFunc,...));
const shufflerEndMark = js.indexOf('));var ');
const shufflerCode = js.substring(0, shufflerEndMark + 2);

// Find the core lookup function
// It's the function that takes 2 args and references the array function
const lookupRegex = new RegExp(`function (_0x\\w+)\\(_0x\\w+,_0x\\w+\\)\\{var _0x\\w+=${arrayFuncName}`);
const lookupNameMatch = js.match(lookupRegex);
let lookupFuncName = null;
if (lookupNameMatch) {
    lookupFuncName = lookupNameMatch[1];
    const lookupIdx = js.indexOf(`function ${lookupFuncName}(`);
    // Find end: lookupFuncName(_0x...,_0x...);}
    const lookupEndStr = `${lookupFuncName}(_0x`;
    // Search for the closing pattern
    let endIdx = js.indexOf(lookupEndStr, lookupIdx + 20);
    // Find the }; after it
    endIdx = js.indexOf(';}', endIdx) + 2;
    const lookupFunc = js.substring(lookupIdx, endIdx);
    
    // Now find the wrapper functions used in writeArg
    const wrapperFuncs = [];
    for (const fn of funcNames) {
        const fnIdx = js.indexOf(`function ${fn}(`);
        if (fnIdx >= 0) {
            const fnEnd = js.indexOf('}', fnIdx + 20);
            wrapperFuncs.push(js.substring(fnIdx, fnEnd + 1));
        }
    }
    
    console.log(`Lookup function: ${lookupFuncName}`);
    console.log(`Array func: ${arrayFunc.length} chars`);
    console.log(`Shuffler: ${shufflerCode.length} chars`);
    console.log(`Lookup: ${lookupFunc.length} chars`);
    console.log(`Wrapper functions: ${wrapperFuncs.length}`);
    
    // Assemble and eval
    const minJs = `
${arrayFunc}
${shufflerCode}
${lookupFunc}
${wrapperFuncs.join('\n')}
var __result = ${writeArg};
__result;
`;
    
    try {
        const result = eval(minJs);
        console.log("\n=== DECODED HTML ===");
        console.log(result);
        
        const m3u8s = result.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
        if (m3u8s) {
            console.log("\n=== M3U8 URLs ===");
            m3u8s.forEach((u, i) => console.log(`${i+1}. ${u}`));
        }
    } catch (e) {
        console.error("Eval error:", e.message);
    }
} else {
    console.log("Could not find lookup function");
}
