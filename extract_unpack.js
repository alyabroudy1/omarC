const fs = require('fs');

try {
    // Read as buffer to avoid encoding assumptions initially
    const buffer = fs.readFileSync('embed_debug.html');

    // Try to convert from likely encodings
    let content = buffer.toString('utf8');
    if (!content.includes('eval(function')) {
        content = buffer.toString('utf16le');
    }

    if (!content.includes('eval(function')) {
        console.log('Could not find eval(function in UTF8 or UTF16LE');
        process.exit(1);
    }

    const startIndex = content.indexOf('eval(function(p,a,c,k,e,d)');
    if (startIndex === -1) {
        console.log('Start index not found');
        process.exit(1);
    }

    // Naively find the end of the packed function .split('|'))
    // It usually ends with .split('|')) or .split('|')))
    const endIndex = content.indexOf(".split('|')))", startIndex);

    if (endIndex !== -1) {
        const code = content.substring(startIndex, endIndex + 13);
        const unpackCode = code.replace(/^eval\(/, 'console.log(');
        fs.writeFileSync('unpack.js', unpackCode);
        console.log('Extracted to unpack.js');
    } else {
        console.log('End index not found');
    }

} catch (e) {
    console.error(e);
}
