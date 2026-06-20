const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('cimanow_detail.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log("Found scripts:", scripts.length);

scripts.forEach((script, i) => {
    const src = script.getAttribute('src');
    const type = script.getAttribute('type');
    const content = script.textContent.trim();
    console.log(`Script ${i}: src='${src || 'none'}', type='${type || 'none'}', length=${content.length}`);
    if (content.length > 0) {
        console.log(`Content snippet: ${content.substring(0, 150)}...\n`);
    }
});
