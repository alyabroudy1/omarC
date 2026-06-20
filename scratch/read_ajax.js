const fs = require('fs');
const code = fs.readFileSync('c:\\Users\\alyab\\AndroidStudioProjects\\cloudstream-standard-v2\\omarC\\temp_deobfuscated_clean.js', 'utf8');

// Find 'ajax' in code and print surrounding 1000 characters
const sCHB = JSON.parse(fs.readFileSync('c:\\Users\\alyab\\AndroidStudioProjects\\cloudstream-standard-v2\\omarC\\sCHB_decrypted.json', 'utf8'));

// Helper to deobfuscate k53x calls if any, but in temp_deobfuscated_clean.js it might be using the sCHB function lookups.
// Let's search for "ajax" string index which is 33
console.log("String index of 'ajax' in sCHB:", sCHB.indexOf('ajax'));
console.log("String index of 'POST' in sCHB:", sCHB.indexOf('POST'));
console.log("String index of 'wp_json' in sCHB:", sCHB.indexOf('wp_json'));

// Let's print out parts of the code around where sCHB is referenced with indices related to ajax.
// Since indices are represented as numbers, they might be obfuscated as math or simple numbers.
// Let's find occurrences of sCHB access in the clean code.
// E.g., we can search for the array name: `sCHB`
let idx = 0;
while (true) {
    idx = code.indexOf('sCHB[', idx);
    if (idx === -1) break;
    const endIdx = code.indexOf(']', idx);
    const indexExpr = code.substring(idx + 5, endIdx);
    // Try to evaluate the index expression if possible
    let evaluated = null;
    try {
        evaluated = eval(indexExpr);
    } catch(e) {
        // expression might use variables
    }
    
    // If evaluated is a number, print it
    if (evaluated !== null) {
        const str = sCHB[evaluated];
        if (['ajax', 'url', 'type', 'POST', 'data', 'success', 'player_api', 'ver', 'rand'].includes(str)) {
            console.log(`Found sCHB[${evaluated}] (${str}) at position ${idx}:`);
            console.log("Context:", code.substring(idx - 100, idx + 400));
            console.log("-----------------------------------------");
        }
    }
    idx += 5;
}
