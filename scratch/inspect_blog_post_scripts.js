const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

console.log("Loading DOM...");
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log(`\nFound ${scripts.length} scripts:`);
scripts.forEach((script, i) => {
    const src = script.getAttribute('src');
    const type = script.getAttribute('type');
    const content = script.textContent.trim();
    console.log(`[Script ${i}]: src="${src}" type="${type}" length=${content.length}`);
    if (!src && content.length > 0) {
        console.log(`--- Content Preview (first 300 chars) ---`);
        console.log(content.substring(0, 300));
        console.log(`----------------------------------------`);
    }
});
