const fs = require('fs');
const jsdom = require('jsdom');

const movieHtml = fs.readFileSync('temp_movie.html', 'utf8');
const dom = new jsdom.JSDOM(movieHtml);
const doc = dom.window.document;

console.log("=== Page Info ===");
// Find all meta tags
const metaTags = [];
doc.querySelectorAll('meta').forEach(m => {
    metaTags.push({ name: m.getAttribute('name'), property: m.getAttribute('property'), content: m.getAttribute('content') });
});
console.log("Meta tags:", metaTags.slice(0, 15));

// Find elements with text containing 'Venom'
console.log("=== Elements containing 'Venom' ===");
const venomElements = [];
function traverse(node) {
    if (node.nodeType === 3) { // Text node
        if (node.nodeValue.includes('Venom')) {
            venomElements.push({ parent: node.parentElement.tagName, class: node.parentElement.className, text: node.nodeValue.trim() });
        }
    }
    for (let child of node.childNodes) {
        traverse(child);
    }
}
traverse(doc.body);
console.log(venomElements.slice(0, 20));

console.log("=== Image tags ===");
const imgs = [];
doc.querySelectorAll('img').forEach(img => {
    imgs.push({ src: img.getAttribute('src'), dataSrc: img.getAttribute('data-src'), alt: img.getAttribute('alt') });
});
console.log(imgs);
