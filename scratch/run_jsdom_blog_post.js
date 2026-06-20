const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

console.log("Loading JSDOM on cimanow_final_chain.html...");
const dom = new JSDOM(html, {
    url: "https://rm.freex2line.online/2020/02/blog-post.html/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

// Set up cookie jar
dom.window.document.cookie = "PHPSESSID=kjaqftqsmsc7qfmai5fljl8vq8";

setTimeout(() => {
    const doc = dom.window.document;
    
    console.log("\nDocument Title after execution:", doc.title);
    
    // Check iframes
    const iframes = doc.querySelectorAll('iframe');
    console.log(`\nFound ${iframes.length} iframes:`);
    iframes.forEach((iframe, i) => {
        console.log(`[Iframe ${i}]: src="${iframe.getAttribute('src')}" class="${iframe.className}" id="${iframe.id}"`);
    });

    // Check tabs/servers lists
    const tabs = doc.querySelectorAll('.tabcontent, .servers, .server-list, [class*="server"], [class*="player"]');
    console.log(`\nFound ${tabs.length} potential server wrapper elements:`);
    tabs.forEach((tab, i) => {
        console.log(`[Tab ${i}]: tagName=${tab.tagName} class="${tab.className}" id="${tab.id}" htmlSnippet=${tab.outerHTML.substring(0, 150)}`);
    });

    // Check lists
    const uls = doc.querySelectorAll('ul');
    console.log(`\nFound ${uls.length} <ul> elements:`);
    uls.forEach((ul, i) => {
        console.log(`[UL ${i}]: class="${ul.className}" id="${ul.id}" children=${ul.children.length}`);
    });

    // Check scripts variables or anything exposed
    console.log("\nChecking window keys:");
    const winKeys = Object.keys(dom.window).filter(k => !k.startsWith('_') && k.length < 30);
    console.log(winKeys.join(', '));

    console.log("\nDone checking.");
    process.exit(0);
}, 6000);
