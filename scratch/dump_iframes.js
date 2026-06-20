const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

const dom = new JSDOM(html, {
    url: "https://rm.freex2line.online/2020/02/blog-post.html/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

dom.window.document.cookie = "PHPSESSID=kjaqftqsmsc7qfmai5fljl8vq8";

setTimeout(() => {
    const doc = dom.window.document;
    const iframes = doc.querySelectorAll('iframe');
    
    console.log(`\nFound ${iframes.length} iframes. Dumping details:`);
    iframes.forEach((iframe, i) => {
        console.log(`\n--- IFRAME ${i} ---`);
        console.log("Outer HTML:", iframe.outerHTML);
        try {
            const innerDoc = iframe.contentDocument || iframe.contentWindow.document;
            console.log("Inner HTML length:", innerDoc.documentElement.innerHTML.length);
            console.log("Inner HTML snippet:", innerDoc.documentElement.innerHTML.substring(0, 500));
        } catch(e) {
            console.log("Error reading inner HTML:", e.message);
        }
    });

    // Also look for any direct watch/play links or script modifications
    console.log("\nDone.");
    process.exit(0);
}, 6000);
