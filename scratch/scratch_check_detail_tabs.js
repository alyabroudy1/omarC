const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_detail.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const tabs = doc.querySelectorAll('.tabcontent');
console.log("Found tabs count in detail page:", tabs.length);
tabs.forEach((tab, idx) => {
    console.log(`Tab ${idx} id="${tab.id}":`);
    console.log(tab.outerHTML.substring(0, 1000));
    console.log("-----------------------------------------");
});
