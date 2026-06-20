const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('temp_series.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

console.log("=== SERIES H1, H2, H3 elements ===");
doc.querySelectorAll('h1, h2, h3').forEach(h => {
    console.log(`${h.tagName} class="${h.className}": ${h.textContent.trim().replace(/\s+/g, ' ')}`);
});
