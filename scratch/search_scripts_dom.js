const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log(`Searching through ${scripts.length} scripts for DOM insertion methods...`);

const keywords = ['document.write', 'innerHTML', 'appendChild', 'createElement', 'iframe', 'src', '$.ajax', '$.post', '$.get', 'fetch', 'XMLHttpRequest', 'wp-admin/admin-ajax.php'];

scripts.forEach((script, i) => {
    const src = script.getAttribute('src');
    const content = script.textContent;
    if (!src && content.length > 0) {
        let found = [];
        keywords.forEach(kw => {
            if (content.includes(kw)) {
                found.push(kw);
            }
        });
        if (found.length > 0) {
            console.log(`[Script ${i}]: length=${content.length} | Keywords: ${found.join(', ')}`);
            // print context snippet
            found.forEach(kw => {
                const idx = content.indexOf(kw);
                console.log(`  Context for "${kw}": ...${content.substring(idx - 50, idx + 100)}...`);
            });
            console.log("-----------------------------------------");
        }
    }
});
