// Try to extract using the actual JS evaluation with a simpler approach
const fs = require('fs');
const vm = require('vm');

const html = fs.readFileSync('/tmp/fasel_test2.html', 'utf8');

// Find the target line
const lines = html.split('\n');
const targetLine = lines.find(l => l.includes('_0x') && (l.includes('hlsPlaylist') || l.includes('document.write')));

if (!targetLine) {
    console.log("ERROR: No target line");
    process.exit(1);
}

let js = targetLine.replace(/<\/?script[^>]*>/gi, '').trim();
const lastSemiHtml = js.lastIndexOf(';<');
if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);

console.log("JS length:", js.length);
console.log("Has hlsPlaylist:", js.includes('hlsPlaylist'));
console.log("Has document.write:", js.includes("document['write']"));

// Check what format this is
const isDocWrite = js.includes("document['write']") || js.includes("document.write");
console.log("Format:", isDocWrite ? "document.write" : "jwplayer/hlsPlaylist");

if (!isDocWrite) {
    // For jwplayer format, let's try extracting the decode functions and evaluate
    
    // Find the string table
    const tableMatch = html.match(/var _0x56e007=\[([^\]]+)\];/);
    if (tableMatch) {
        console.log("\nFound string table");
        const strings = tableMatch[1].match(/"[^"]+"/g);
        console.log("String count:", strings?.length);
    }
    
    // Try direct extraction - look for patterns
    console.log("\n=== Direct URL search ===");
    
    // The file URL is in _0x5e3ffb['file']
    const fileStart = js.indexOf("_0x5e3ffb['file']=");
    if (fileStart >= 0) {
        const fileSection = js.substring(fileStart, fileStart + 600);
        console.log("File section:", fileSection.slice(0, 300));
    }
    
    // Try to find any complete URLs in the entire HTML
    console.log("\n=== All URL patterns ===");
    const allUrls = html.match(/https:\/\/[^\s"'>]+/g);
    if (allUrls) {
        const uniqueUrls = [...new Set(allUrls)];
        console.log("Unique URLs:", uniqueUrls.length);
        
        // Filter for video URLs
        const videoUrls = uniqueUrls.filter(u => 
            u.includes('.m3u8') || 
            u.includes('scdns') ||
            u.includes('video') ||
            u.includes('stream')
        );
        console.log("\nVideo URLs:", videoUrls);
        
        // Check for any m3u8
        const m3u8Urls = uniqueUrls.filter(u => u.includes('.m3u8'));
        console.log("\nM3U8 URLs:", m3u8Urls);
    }
}
