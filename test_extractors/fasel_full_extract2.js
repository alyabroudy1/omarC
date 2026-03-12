// Full extraction test - include everything up to hlsPlaylist
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');
const lines = html.split('\n');
const targetLine = lines.find(l => l.includes('_0x') && l.includes('hlsPlaylist'));

let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

// Get everything from the start up to (but not including) mainPlayer setup
const mainPlayerIdx = js.indexOf('mainPlayer[');
if (mainPlayerIdx > 0) {
    js = js.substring(0, mainPlayerIdx);
}

console.log("JS to eval:", js.length, "chars");

// Now try to evaluate
console.log("\n=== Running eval ===");
try {
    // Mock browser globals
    const mockGlobals = `
        var window = this;
        var document = { 
            write: function(s) { this._output = s; }, 
            createElement: function() { return { src: '', setAttribute: function() {} }; },
            head: {},
            body: {}
        };
        var console = { log: function() {}, error: function() {} };
        var mainPlayer = { setup: function() {}, on: function() {} };
    `;
    
    const result = eval(mockGlobals + js + "; JSON.stringify(typeof hlsPlaylist !== 'undefined' ? hlsPlaylist : 'not defined');");
    console.log("Result:", result.slice(0, 200));
    
    // Try to parse and get the actual URL
    const parsed = JSON.parse(result);
    console.log("\nSources:", parsed.sources);
    if (parsed.sources && parsed.sources[0]) {
        console.log("\nFile URL:", parsed.sources[0].file);
    }
} catch(e) {
    console.log("Error:", e.message);
}
