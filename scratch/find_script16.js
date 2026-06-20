const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
const script16 = scripts[16]; // from the output of inspect_blog_post_scripts, Script 16 is at index 16
if (script16) {
    const content = script16.textContent.trim();
    console.log("Script 16 length:", content.length);
    console.log("Starts with:", content.substring(0, 100));
    console.log("Ends with:", content.substring(content.length - 200));
    
    // Convert codes array to string if it matches
    const arrayMatch = content.match(/const codes = \[(.*?)\];/);
    if (arrayMatch) {
        const codesStr = arrayMatch[1];
        const codes = codesStr.split(',').map(Number);
        const decoded = String.fromCharCode(...codes);
        console.log("\nDecoded Script 16 Length:", decoded.length);
        console.log("Decoded Script 16 Snippet (first 1000 chars):");
        console.log(decoded.substring(0, 1000));
        fs.writeFileSync('scratch/decoded_script16.js', decoded);
    } else {
        console.log("No const codes array found in script 16 content.");
    }
} else {
    console.log("Script 16 not found.");
}
