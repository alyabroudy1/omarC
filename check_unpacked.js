const fs = require('fs');

try {
    // Try reading as UTF-16LE since PowerShell > often creates that
    let content;
    try {
        content = fs.readFileSync('unpacked_source.js', 'utf16le');
    } catch (e) {
        content = fs.readFileSync('unpacked_source.js', 'utf8');
    }

    console.log(`Read ${content.length} characters`);

    // Regex from Kotlin
    const regex = /sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']/;
    const match = content.match(regex);

    if (match) {
        console.log("MATCH FOUND:", match[1]);
    } else {
        console.log("NO MATCH FOUND");
        console.log("Snippet (first 500 chars):", content.substring(0, 500));
    }

} catch (e) {
    console.error(e);
}
