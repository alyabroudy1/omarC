const fs = require('fs');

const decrypted = fs.readFileSync('cimanow_watch_noon_decrypted.html');
console.log("File byte length:", decrypted.length);

// Count null bytes
let nullCount = 0;
for (let i = 0; i < decrypted.length; i++) {
    if (decrypted[i] === 0) nullCount++;
}
console.log("Null bytes count:", nullCount);

// Print last 200 characters (ignoring nulls or showing them as [NULL])
let lastChars = '';
const start = Math.max(0, decrypted.length - 200);
for (let i = start; i < decrypted.length; i++) {
    const b = decrypted[i];
    if (b === 0) {
        lastChars += '[NULL]';
    } else {
        lastChars += String.fromCharCode(b);
    }
}
console.log("\nLast 200 bytes of decrypted HTML:");
console.log(lastChars);
