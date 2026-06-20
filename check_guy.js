const fs = require('fs');
const html = fs.readFileSync('temp_watch_guy.html', 'utf8');
console.log('Contains vkvideo:', html.includes('vkvideo'));
console.log('Contains iframe:', html.includes('<iframe'));
const ulBtns = html.match(/<ul class=["']btns["']>([\s\S]*?)<\/ul>/i);
if (ulBtns) console.log('ul.btns:', ulBtns[1]);
else console.log('No ul.btns');
