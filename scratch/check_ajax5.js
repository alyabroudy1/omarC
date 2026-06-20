const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');

console.log('Number of lines:', lines.length);

// Check if 'DisableDevtool' is in line 2
const line2 = lines[1];
const occurrences = [];
let index = line2.indexOf('DisableDevtool');
while (index !== -1) {
    occurrences.push(index);
    index = line2.indexOf('DisableDevtool', index + 1);
}
console.log('Occurrences of DisableDevtool in line 2:', occurrences.length);
if (occurrences.length > 0) {
    console.log('First few surrounding characters:', occurrences.map(idx => line2.substring(idx - 20, idx + 40)));
} else {
    console.log('No occurrences in line 2. Let us search case-insensitively for "disable" in line 2:');
    let idx2 = line2.toLowerCase().indexOf('disable');
    if (idx2 !== -1) {
        console.log('Found "disable" at index:', idx2);
        console.log('Surrounding:', line2.substring(idx2 - 20, idx2 + 40));
    }
}
