// Full extraction test with the new format
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');
const lines = html.split('\n');
const targetLine = lines.find(l => l.includes('_0x') && l.includes('hlsPlaylist'));

let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

// Find key components
// 1. String array function _0x51c0
const stringArrayStart = js.indexOf('function _0x51c0()');
const stringArrayEnd = js.indexOf('return _0x51c0();}', stringArrayStart);
const stringArrayFunc = js.substring(stringArrayStart, stringArrayEnd + 'return _0x51c0();}'.length);

// 2. The decode function _0x14e4
const decodeStart = js.indexOf('function _0x14e4');
const decodeEnd = js.indexOf('var _0x443697', decodeStart);
const decodeFunc = js.substring(decodeStart, decodeEnd);

// 3. URL builder functions _0x373126 and _0x492df0
const urlBuilderStart = js.indexOf('function _0x373126');
const urlBuilderEnd = js.indexOf('var hlsPlaylist', urlBuilderStart);
const urlBuilderFuncs = js.substring(urlBuilderStart, urlBuilderEnd);

// 4. The hlsPlaylist object _0x5e3ffb
const playlistStart = js.indexOf('_0x5e3ffb={}');
const playlistEnd = js.indexOf('var hlsPlaylist=', playlistStart);
const playlistObj = js.substring(playlistStart, playlistEnd);

// 5. The shuffler IIFE - find the (function(_0x...){...}(_0x... pattern at start
const shufflerEnd = js.indexOf('));var ');
const shufflerCode = js.substring(0, shufflerEnd + 2);

console.log("Components found:");
console.log("String array:", stringArrayFunc.length, "chars");
console.log("Decode func:", decodeFunc.length, "chars");
console.log("URL builders:", urlBuilderFuncs.length, "chars");
console.log("Playlist obj:", playlistObj.length, "chars");
console.log("Shuffler:", shufflerCode.length, "chars");

// Build eval script
const evalScript = `
${stringArrayFunc}
${shufflerCode}
${decodeFunc}
${urlBuilderFuncs}
${playlistObj}

// Now evaluate to get hlsPlaylist
var hlsPlaylist = _0x5e3ffb;
JSON.stringify(hlsPlaylist);
`;

console.log("\n=== Running eval ===");
try {
    // In Node.js, we need to mock some browser globals
    const result = eval(evalScript);
    console.log("Result:", result ? "Success" : "Failed");
    if (result) {
        const parsed = JSON.parse(result);
        console.log("\nPlaylist sources:");
        if (parsed.sources && parsed.sources[0]) {
            console.log("File:", parsed.sources[0].file);
            console.log("Label:", parsed.sources[0].label);
        }
    }
} catch(e) {
    console.log("Error:", e.message);
    console.log("Stack:", e.stack?.split('\n').slice(0, 5).join('\n'));
}
