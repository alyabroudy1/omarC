const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
console.log(`Searching through ${scripts.length} scripts for 'location' or 'replace'...`);

scripts.forEach((script, i) => {
    const src = script.getAttribute('src');
    const content = script.textContent;
    if (!src && content.length > 0) {
        const matchesLocation = content.match(/location/gi);
        const matchesReplace = content.match(/replace/gi);
        const matchesAssign = content.match(/assign/gi);
        const matchesHref = content.match(/href/gi);
        if (matchesLocation || matchesReplace || matchesAssign || matchesHref) {
            console.log(`[Script ${i}]: length=${content.length}`);
            console.log(`  location: ${matchesLocation ? matchesLocation.length : 0}`);
            console.log(`  replace: ${matchesReplace ? matchesReplace.length : 0}`);
            console.log(`  assign: ${matchesAssign ? matchesAssign.length : 0}`);
            console.log(`  href: ${matchesHref ? matchesHref.length : 0}`);
            
            // Let's print some lines containing these keywords if they are small enough, or regex match
            const regex = /.{0,50}(location|replace|assign|href).{0,50}/gi;
            const contextMatches = content.match(regex);
            if (contextMatches) {
                console.log("  Context examples (up to 5):");
                contextMatches.slice(0, 5).forEach(m => console.log(`    - ${m.trim()}`));
            }
            console.log("-----------------------------------------");
        }
    }
});
