const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log(`Analyzing ${scripts.length} scripts...`);

scripts.forEach((script, i) => {
    const src = script.getAttribute('src');
    const content = script.textContent.trim();
    if (!src && content.length > 0) {
        console.log(`\n[Script ${i}]: length=${content.length}`);
        
        // Print signature/structure
        const lines = content.split('\n');
        console.log(`  Lines: ${lines.length}`);
        console.log(`  Preview: ${content.substring(0, 400)}`);
        
        // Look for large arrays or hex structures
        const largeHexArray = content.match(/_0x[0-9a-f]+s?s?s?s?s?/gi);
        if (largeHexArray) {
            console.log(`  Contains _0x variables: ${largeHexArray.length}`);
        }
        if (content.includes('eval(')) {
            console.log(`  Contains 'eval('`);
        }
        if (content.includes('Function(')) {
            console.log(`  Contains 'Function('`);
        }
    }
});
