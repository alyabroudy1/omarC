const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

// Script 12 is at index 12 in the document
const scripts = doc.querySelectorAll('script');
const script12Content = scripts[12].textContent;

// Let's print the start of the script to see what functions it defines
console.log("Script 12 Start (first 3500 chars):");
console.log(script12Content.substring(0, 3500));

console.log("\nDone.");
process.exit(0);
