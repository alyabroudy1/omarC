const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('temp_series.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

console.log("=== Series layout elements ===");
// Find the links going to '/episodes/' and print their parent tags, class, etc.
doc.querySelectorAll('a').forEach(a => {
    const href = a.getAttribute('href') || '';
    if (href.includes('/episodes/') || href.includes('/episode/')) {
        let path = [];
        let p = a.parentElement;
        while (p && p.tagName !== 'BODY') {
            path.push(p.tagName + (p.className ? '.' + p.className.replace(/\s+/g, '.') : '') + (p.id ? '#' + p.id : ''));
            p = p.parentElement;
        }
        console.log(`Link: ${href} | Text: ${a.textContent.trim()} | Path: ${path.reverse().join(' > ')}`);
    }
});
