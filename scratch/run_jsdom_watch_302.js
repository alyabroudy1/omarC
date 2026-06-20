const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_watch_302.html', 'utf8');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

console.log("Loading JSDOM on cimanow_watch_302.html...");
const dom = new JSDOM(html, {
    url: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/watching/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

setTimeout(() => {
    const doc = dom.window.document;
    console.log("\n--- DOM INSPECTION ---");
    console.log("Title after JS execution:", doc.title);
    
    const servers = doc.querySelectorAll('ul.tabcontent li');
    console.log(`Found ${servers.length} server elements!`);
    servers.forEach((s, i) => {
        console.log(`  [Server ${i}]: name="${s.textContent.trim()}" index="${s.getAttribute('data-index')}" id="${s.getAttribute('data-id')}"`);
    });

    console.log("\nDone.");
    process.exit(0);
}, 5000);
