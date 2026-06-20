const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_detail.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const btns = doc.querySelectorAll('.btns a');
console.log("Found buttons:", btns.length);
btns.forEach((a, i) => {
    const text = a.textContent.trim();
    const href = a.getAttribute('href') || '';
    console.log(`Button ${i}: text='${text}', href='${href}'`);
    if (href.includes('link=')) {
        const base64Part = href.substring(href.indexOf('link=') + 5).split('&')[0];
        try {
            const decoded = Buffer.from(base64Part, 'base64').toString('utf8');
            console.log(`  Decoded link: ${decoded}`);
        } catch (e) {
            console.error(`  Failed to decode Base64:`, e.message);
        }
    }
});
