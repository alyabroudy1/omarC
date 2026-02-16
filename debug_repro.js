const fs = require('fs');

function unpack(p, a, c, k, e, d) {
    while (c--) if (k[c]) p = p.replace(new RegExp('\\b' + c.toString(a) + '\\b', 'g'), k[c]);
    return p;
}

// Minimal re-implementation of the unpacker logic typically found in these packed scripts
// Note: Real JsUnpacker is more complex, but usually these sites use the standard P.A.C.K.E.R.
// We will look for the arguments in the file.

try {
    const html = fs.readFileSync('embed_clean.html', 'utf8');
    console.log(`Read ${html.length} bytes`);

    // 1. Extract packed code
    const packedStartMarker = 'eval(function(p,a,c,k,e,d)';
    const packedEndMarker = '</script>';

    const startIndex = html.indexOf(packedStartMarker);
    if (startIndex === -1) {
        console.log('Packed JS not found (marker mismatch?)');
        // fallback to full text
        checkRegex(html, "Raw HTML");
        return;
    }

    const endIndex = html.indexOf(packedEndMarker, startIndex);
    const packedBlock = html.substring(startIndex, endIndex);

    console.log('Found packed block length:', packedBlock.length);

    // 2. Unpack (Simulation/Hack)
    // Since we can't easily run the full JSUnpacker in clean node without the library, 
    // and I don't want to overengineer the JS script if I can just "eval" the packer if it's standard.
    // However, safest is to just print it and see if we can use a simpler regex or if the Kotlin JsUnpacker is failing.
    // 
    // Actually, asking the user to run "node extract_unpack.js" might be better if it works.
    // Let's rely on regex first on the raw file to see if it's there WITHOUT unpacking (unlikely for OkPrime).

    // Attempt to extract arguments for the packer
    // eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String)){while(c--)d[e(c)]=k[c]||e(c);k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p}('payload',radix,count,['keywords'],0,{}))

    // We will just save the packed part to `packed.js` and try to run it via console.log substitution roughly
    // Or simpler: The user said "proof it works".

    // Let's try to match the SOURCES regex against the raw HTML first.
    checkRegex(html, "Raw HTML");

} catch (e) {
    console.error("Error:", e.message);
}

function checkRegex(text, sourceName) {
    // Regex from Kotlin: sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']
    const regex = /sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']/;
    const match = text.match(regex);
    if (match) {
        console.log(`[${sourceName}] FOUND:`, match[1]);
    } else {
        console.log(`[${sourceName}] NOT FOUND (Regex mismatch)`);
    }
}
