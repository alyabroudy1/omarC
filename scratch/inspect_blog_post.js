const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

console.log("Loading DOM...");
const dom = new JSDOM(html);
const doc = dom.window.document;

console.log("Title:", doc.title);

// Check iframes
const iframes = doc.querySelectorAll('iframe');
console.log(`\nFound ${iframes.length} iframes:`);
iframes.forEach((iframe, i) => {
    console.log(`[Iframe ${i}]: src="${iframe.getAttribute('src')}" class="${iframe.className}" id="${iframe.id}"`);
});

// Check tabs/servers lists
const tabs = doc.querySelectorAll('.tabcontent, .servers, .server-list, [class*="server"], [class*="player"]');
console.log(`\nFound ${tabs.length} potential server wrapper elements:`);
tabs.forEach((tab, i) => {
    console.log(`[Tab ${i}]: tagName=${tab.tagName} class="${tab.className}" id="${tab.id}" htmlSnippet=${tab.outerHTML.substring(0, 150)}`);
});

// Check general lists
const uls = doc.querySelectorAll('ul');
console.log(`\nFound ${uls.length} <ul> elements:`);
uls.forEach((ul, i) => {
    console.log(`[UL ${i}]: class="${ul.className}" id="${ul.id}" children=${ul.children.length}`);
    if (ul.children.length > 0) {
        console.log(`  First child: ${ul.children[0].outerHTML.substring(0, 200)}`);
    }
});

// Look for data-index/data-id or similar attributes in li
const lis = doc.querySelectorAll('li[data-index], li[data-id], li[onclick]');
console.log(`\nFound ${lis.length} <li> elements with click/data attrs:`);
lis.forEach((li, i) => {
    if (i < 20) {
        console.log(`[LI ${i}]: text="${li.textContent.trim()}" index="${li.getAttribute('data-index')}" id="${li.getAttribute('data-id')}" onclick="${li.getAttribute('onclick')}"`);
    }
});
