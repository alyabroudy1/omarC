const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('temp_movie.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

// Let's print out all divs/paragraphs that have text longer than 50 characters
console.log("=== Text elements longer than 50 chars ===");
const elements = doc.querySelectorAll('div, p, span, section, article');
elements.forEach(el => {
    // only leaf-ish elements or elements with short children
    if (el.children.length <= 1) {
        const text = el.textContent.trim().replace(/\s+/g, ' ');
        if (text.length > 50 && text.length < 1000) {
            console.log(`${el.tagName} class="${el.className}" id="${el.id}":`);
            console.log(`  ${text}`);
        }
    }
});
