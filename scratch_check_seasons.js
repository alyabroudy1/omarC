const fs = require('fs');
const jsdom = require('jsdom');

const html = fs.readFileSync('temp_series.html', 'utf8');
const dom = new jsdom.JSDOM(html);
const doc = dom.window.document;

console.log("=== Checking seasons ===");
// Seasons are typically represented by buttons or list items or tabs. Let's look for anything with "season" class
const seasons = doc.querySelectorAll('[class*="season"], [id*="season"]');
seasons.forEach(s => {
    console.log(`Tag: ${s.tagName} | Class: ${s.className} | ID: ${s.id} | Text: ${s.textContent.trim().substring(0, 100)}`);
});
