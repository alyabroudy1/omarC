const fs = require('fs');

const raw = fs.readFileSync('cimanow_watch_noon.html', 'utf8');
console.log("Raw file length:", raw.length);

const lines = raw.split('\n');
console.log("Raw file line count:", lines.length);

// Print the last 5 lines
console.log("\nLast 5 lines of raw HTML:");
for (let i = Math.max(0, lines.length - 5); i < lines.length; i++) {
    console.log(`${i + 1}: ${lines[i].substring(0, 100)}`);
}

// Find if there is any other HTML structure in the raw file outside the script
const rawWithoutScript = raw.replace(/<script[\s\S]*?<\/script>/gi, '');
console.log("\nLength without script tags:", rawWithoutScript.length);
console.log("Preview of raw content without script:");
console.log(rawWithoutScript.substring(0, 500));
