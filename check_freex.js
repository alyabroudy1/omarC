const fs = require('fs');
const html = fs.readFileSync('cimanow_episode.html', 'utf8');
const match = html.match(/href=["'](https?:\/\/[^"']*freex2line[^"']*)["']/i);
console.log(match ? match[1] : 'not found');
