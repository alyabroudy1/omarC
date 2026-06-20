const fs = require('fs');
const file = 'cimanow_detail.html';

try {
    const stats = fs.statSync(file);
    console.log("File size:", stats.size);
    const buffer = fs.readFileSync(file);
    console.log("First 100 bytes:", buffer.slice(0, 100));
    const text = buffer.toString('utf8');
    console.log("First 200 chars as UTF8:", text.substring(0, 200));
    console.log("Does text contain '<script'?", text.toLowerCase().includes('<script'));
    console.log("Does text contain 'cimanow'?", text.toLowerCase().includes('cimanow'));
} catch (e) {
    console.error(e);
}
