const fs = require('fs');
const content = fs.readFileSync('unpack.js', 'utf8');
const match = content.match(/sources:(\[.*?\])/);
if (match) {
    console.log(match[1]);
} else {
    console.log('No sources found');
}
