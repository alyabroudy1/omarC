const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Find occurrences of ";!" or ");" in line 2
const parts = [];
let index = 0;
while (true) {
    let nextIndex = line2.indexOf(';', index);
    if (nextIndex === -1) {
        parts.push(line2.substring(index));
        break;
    }
    parts.push(line2.substring(index, nextIndex + 1));
    index = nextIndex + 1;
}

console.log(`Line 2 has ${parts.length} parts (split by semi-colon)`);
parts.forEach((p, i) => {
    if (p.length > 50) {
        console.log(`Part ${i} (length ${p.length}):`);
        console.log("  Start:", p.substring(0, 150));
        console.log("  End:", p.substring(p.length - 150));
    }
});
