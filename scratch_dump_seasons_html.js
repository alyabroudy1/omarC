const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('temp_series.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

const s = doc.querySelector('.seasonse');
if (s) {
    console.log("=== .seasonse outerHTML ===");
    console.log(s.outerHTML);
    console.log("=== Parent outerHTML ===");
    console.log(s.parentElement.outerHTML.substring(0, 1000));
} else {
    console.log(".seasonse not found");
}
