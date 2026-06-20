const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_detail.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

console.log("Detail title:", doc.title);
console.log("Detail body innerHTML length:", doc.body ? doc.body.innerHTML.length : 0);

const scripts = doc.querySelectorAll('script');
console.log("Number of scripts:", scripts.length);
scripts.forEach((s, i) => {
    const src = s.getAttribute('src');
    const content = s.textContent.trim();
    console.log(`Script ${i}: src="${src}" length=${content.length}`);
    if (content.length > 0) {
        console.log(`  Snippet: ${content.substring(0, 200)}...`);
    }
});

const elements = doc.querySelectorAll('body *');
console.log("\nTotal body elements:", elements.length);
const tags = {};
elements.forEach(el => {
    tags[el.tagName] = (tags[el.tagName] || 0) + 1;
});
console.log("Tag counts:", tags);
