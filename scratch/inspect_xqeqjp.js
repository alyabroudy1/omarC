const fs = require('fs');
const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

const lines = html.split('\n');
lines.forEach((line, i) => {
    if (line.includes('xqeqjp')) {
        console.log(`Line ${i + 1}: ${line.trim().substring(0, 200)}`);
    }
});
