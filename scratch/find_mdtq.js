const fs = require('fs');
const code = fs.readFileSync('c:\\Users\\alyab\\AndroidStudioProjects\\cloudstream-standard-v2\\omarC\\temp_deobfuscated_clean.js', 'utf8');

// The pathname property is accessed somewhere.
// Let's find "pathname" in the code. Since we know from the decrypted strings that
// "pathname" is at index 87 of sCHB, let's find where index 87 is accessed or where the URL length is calculated.
// Let's search for "replace" (index 88), "slice" (index 89).
// Let's search for occurrences of functions.
// We can search for the text "replace" or "/^\\/|\\/$/g" (regex pattern).
const idx = code.indexOf('/^\\/|\\/$/g');
console.log("Regex pattern index:", idx);
if (idx !== -1) {
    console.log("Context:", code.substring(idx - 200, idx + 400));
} else {
    // If not found as literal, let's look for how it's defined.
    // Let's search for sCHB index or search for code structures.
    console.log("Regex literal not found");
}

// Let's find any function containing slice
const sliceIdx = code.indexOf('slice');
console.log("Slice index in code:", sliceIdx);
if (sliceIdx !== -1) {
    console.log("Slice Context:", code.substring(sliceIdx - 200, sliceIdx + 400));
}
