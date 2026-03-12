// Test extraction - prove it works
const fs = require('fs');

const html = fs.readFileSync('/tmp/fasel_test.html', 'utf8');
const lines = html.split('\n');

// Find the target line with obfuscated JS
const targetLine = lines.find(l => l.includes('_0x') && (l.includes('hlsPlaylist') || l.includes('document.write')));
if (!targetLine) {
    console.log("ERROR: No target line found");
    process.exit(1);
}

let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

const isDocumentWrite = js.includes("document['write']") || js.includes("document.write");
console.log("Format:", isDocumentWrite ? "document.write" : "jwplayer/hlsPlaylist");

// For jwplayer format - we need to evaluate the JS
if (!isDocumentWrite) {
    const mainPlayerIdx = js.indexOf("mainPlayer[");
    if (mainPlayerIdx < 0) {
        console.log("ERROR: No mainPlayer setup");
        process.exit(1);
    }

    const scriptBody = js.substring(0, mainPlayerIdx);
    const iifeStart = scriptBody.indexOf("(function(");
    if (iifeStart < 0) {
        console.log("ERROR: No IIFE found");
        process.exit(1);
    }

    const hlsAssignIdx = scriptBody.indexOf("var hlsPlaylist=");
    if (hlsAssignIdx < 0) {
        console.log("ERROR: No hlsPlaylist assignment");
        process.exit(1);
    }

    const evalJs = scriptBody.substring(iifeStart, hlsAssignIdx + 50);
    
    const fullEvalJs = `
        var window = this;
        var document = { write: function(){}, createElement: function(){return {};}, head:{}, body:{} };
        var mainPlayer = { setup: function(){return this;}, on: function(){} };
        var console = { log: function(){}, error: function(){} };
        var jwplayer = function() { return mainPlayer; };
        var _0x51c0 = function() { return []; };
    ` + evalJs + `
        var hlsPlaylist = typeof _0x5e3ffb !== 'undefined' ? _0x5e3ffb : null;
        if (hlsPlaylist) { JSON.stringify(hlsPlaylist); } else { 'null'; }
    `;

    try {
        const result = eval(fullEvalJs);
        if (result && result !== 'null') {
            console.log("\n=== EXTRACTED PLAYLIST JSON ===");
            const parsed = JSON.parse(result);
            console.log("Sources:", parsed.sources);
            if (parsed.sources && parsed.sources[0]) {
                console.log("\n=== VIDEO URL ===");
                console.log(parsed.sources[0].file);
            }
        } else {
            console.log("Result was null, trying fallback...");
            // Fallback: regex for m3u8
            const m3u8Urls = js.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
            if (m3u8Urls) {
                console.log("\n=== FALLBACK: M3U8 URLs ===");
                m3u8Urls.forEach(u => console.log(u));
            }
        }
    } catch(e) {
        console.log("Eval error:", e.message);
        // Try fallback
        const m3u8Urls = js.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
        if (m3u8Urls) {
            console.log("\n=== FALLBACK: M3U8 URLs ===");
            m3u8Urls.forEach(u => console.log(u));
        }
    }
}
