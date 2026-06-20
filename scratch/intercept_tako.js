const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

console.log("Setting up JSDOM with fetch interception...");
const dom = new JSDOM(html, {
    url: "https://rm.freex2line.online/2020/02/blog-post.html/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

// Intercept window.fetch
dom.window.fetch = async (url, options) => {
    console.log("\n[INTERCEPTED FETCH]:");
    console.log("URL:", url);
    console.log("Options:", JSON.stringify(options, null, 2));
    
    // We can return a dummy response or perform the real fetch to see what it returns
    try {
        console.log("Performing real fetch inside interceptor...");
        const res = await globalThis.fetch(url, options);
        const text = await res.text();
        console.log(`Response Status: ${res.status}`);
        console.log(`Response Body Length: ${text.length}`);
        console.log(`Response Body Snippet: ${text.substring(0, 500)}`);
        
        return {
            status: res.status,
            ok: res.ok,
            headers: res.headers,
            text: async () => text,
            json: async () => JSON.parse(text)
        };
    } catch (e) {
        console.error("Fetch implementation failed:", e.message);
        return {
            status: 200,
            ok: true,
            text: async () => "",
            json: async () => ({})
        };
    }
};

setTimeout(() => {
    console.log("\nExecution finished.");
    process.exit(0);
}, 6000);
