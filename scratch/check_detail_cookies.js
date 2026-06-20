const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_detail.html', 'utf8');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

console.log("Loading JSDOM on cimanow_detail.html...");
const dom = new JSDOM(html, {
    url: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

setTimeout(() => {
    console.log("\nCookies after execution:", dom.window.document.cookie);
    console.log("Done.");
    process.exit(0);
}, 5000);
