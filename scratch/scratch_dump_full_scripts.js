const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_watch_decrypted_full.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log("Found scripts in full decrypted HTML:", scripts.length);

scripts.forEach((s, idx) => {
    const src = s.getAttribute('src');
    const content = s.textContent.trim();
    if (content.length > 0) {
        const filename = `scratch_full_script_${idx}.js`;
        fs.writeFileSync(filename, content);
        console.log(`Saved script ${idx} to ${filename} (size: ${content.length} bytes)`);
    } else {
        console.log(`Script ${idx} is external: src="${src}"`);
    }
});
