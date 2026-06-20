const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('cimanow_detail.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
const script15 = scripts[15].textContent.trim();

console.log("Script 15 length:", script15.length);
console.log("Script 15 start (2000 chars):\n", script15.substring(0, 2000));
fs.writeFileSync('script15.js', script15);
