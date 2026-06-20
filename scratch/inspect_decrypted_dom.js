const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_watch_decrypted.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

console.log("Title of Decrypted watch HTML:", doc.title);
console.log("Body length:", doc.body ? doc.body.innerHTML.length : 0);

// Check iframes
const iframes = doc.querySelectorAll('iframe');
console.log(`Iframes: ${iframes.length}`);
iframes.forEach((iframe, i) => {
    console.log(`  [Iframe ${i}]: src="${iframe.getAttribute('src')}" class="${iframe.className}" id="${iframe.id}"`);
});

// Check all elements in body
const bodyChildren = doc.body ? doc.body.children : [];
console.log(`Body top-level children: ${bodyChildren.length}`);
for (let i = 0; i < bodyChildren.length; i++) {
    const child = bodyChildren[i];
    console.log(`  Child ${i}: tagName=${child.tagName} id="${child.id}" class="${child.className}" htmlSnippet=${child.outerHTML.substring(0, 150)}`);
}

// Check scripts
const scripts = doc.querySelectorAll('script');
console.log(`Scripts inside decrypted HTML: ${scripts.length}`);
scripts.forEach((script, i) => {
    console.log(`  [Script ${i}]: src="${script.getAttribute('src')}" type="${script.getAttribute('type')}" content.length=${script.textContent.trim().length}`);
});
