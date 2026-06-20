const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_watch_noon.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log("Total scripts in raw watch html:", scripts.length);
scripts.forEach((s, i) => {
    const src = s.getAttribute('src');
    const content = s.textContent.trim();
    console.log(`Script ${i}: src="${src}" length=${content.length}`);
    if (content.length > 0) {
        console.log("  Start:", content.substring(0, 150));
        // Check for obfuscated variables (starting with var _xxx)
        const vars = content.match(/var\s+(\w+)\s*=/g);
        if (vars) {
            console.log("  Variables defined:", vars.map(v => v.replace('var', '').replace('=', '').trim()));
        }
    }
});
