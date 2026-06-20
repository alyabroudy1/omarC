const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_detail.html', 'utf8');

// Set up virtual console
const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

console.log("Loading JSDOM...");
const dom = new JSDOM(html, {
    url: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

// Wait a bit for document ready scripts to execute
setTimeout(() => {
    const doc = dom.window.document;
    
    // Print title
    console.log("Document Title after execution:", doc.title);
    
    // Search for watch/watching/play buttons
    console.log("\nSearching for links after JS execution:");
    const links = doc.querySelectorAll('a');
    let watchLinks = [];
    links.forEach(a => {
        const href = a.getAttribute('href') || '';
        if (href.includes('watch') || href.includes('play') || href.includes('server')) {
            watchLinks.push({ text: a.textContent.trim(), href: href });
        }
    });
    console.log("Found watch links:", watchLinks);
    
    // Search for lists of servers or tabs
    const serverTabs = doc.querySelectorAll('.tabcontent, .servers, .server-list, [class*="server"], [class*="player"]');
    console.log("\nFound server elements:", serverTabs.length);
    serverTabs.forEach((tab, i) => {
        console.log(`Tab ${i}: tag=${tab.tagName}, class='${tab.className}', htmlSnippet=${tab.outerHTML.substring(0, 150)}`);
    });

    // Check for any lists
    const uls = doc.querySelectorAll('ul');
    console.log("\nFound Uls:", uls.length);
    uls.forEach((ul, i) => {
        console.log(`UL ${i}: class='${ul.className}', children=${ul.children.length}`);
        if (ul.children.length > 0) {
            console.log(`  First child:`, ul.children[0].outerHTML.substring(0, 100));
        }
    });

}, 3000);
