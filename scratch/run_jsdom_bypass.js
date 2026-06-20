const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[Console Log]:", ...args));
virtualConsole.on("error", (...args) => console.error("[Console Err]:", ...args));

console.log("Setting up JSDOM with canvas bypass...");
const dom = new JSDOM(html, {
    url: "https://rm.freex2line.online/2020/02/blog-post.html/",
    runScripts: "dangerously",
    resources: "usable",
    virtualConsole: virtualConsole
});

// Implement bypasses on the window object before scripts run
dom.window.createImageBitmap = async (blob) => {
    console.log("[Bypass] createImageBitmap called!");
    return { width: 100, height: 100 };
};

// Override canvas getContext on HTMLCanvasElement prototype
const originalGetContext = dom.window.HTMLCanvasElement.prototype.getContext;
dom.window.HTMLCanvasElement.prototype.getContext = function(type) {
    if (type === '2d') {
        console.log("[Bypass] canvas getContext('2d') called!");
        return {
            drawImage: () => {},
            getImageData: (x, y, w, h) => {
                console.log(`[Bypass] getImageData called: ${x}, ${y}, ${w}, ${h}`);
                const data = new Uint8ClampedArray(w * h * 4);
                data[0] = 1;
                data[4] = 2; // different pixel values to make kntbosRxsYmzJ$DYE = false
                return { data: data };
            }
        };
    }
    return originalGetContext.apply(this, arguments);
};

// Also mock global fetch to succeed for the dynamic image fetch
const originalFetch = dom.window.fetch;
dom.window.fetch = async (url, options) => {
    console.log("[Bypass Intercepted Fetch]:", url);
    if (url.includes('rm.freex2line.online')) {
        console.log("[Bypass] Returning dummy image blob for freex2line fetch");
        return {
            ok: true,
            status: 200,
            blob: async () => {
                return { size: 1000, type: 'image/png' };
            },
            text: async () => ""
        };
    }
    // Fallback to normal fetch for other domains (ads, doubleclick, etc) if needed
    try {
        const res = await globalThis.fetch(url, options);
        const text = await res.text();
        return {
            status: res.status,
            ok: res.ok,
            text: async () => text
        };
    } catch(e) {
        return {
            status: 200,
            ok: true,
            text: async () => ""
        };
    }
};

setTimeout(() => {
    const doc = dom.window.document;
    console.log("\n--- DOM INSPECTION ---");
    console.log("Title:", doc.title);
    
    const iframes = doc.querySelectorAll('iframe');
    console.log(`\nFound ${iframes.length} iframes:`);
    iframes.forEach((iframe, i) => {
        console.log(`[Iframe ${i}]: src="${iframe.getAttribute('src')}" class="${iframe.className}" id="${iframe.id}"`);
    });

    const tabs = doc.querySelectorAll('.tabcontent, .servers, .server-list, [class*="server"], [class*="player"]');
    console.log(`\nFound ${tabs.length} potential server wrapper elements:`);
    tabs.forEach((tab, i) => {
        console.log(`[Tab ${i}]: tagName=${tab.tagName} class="${tab.className}" id="${tab.id}" htmlSnippet=${tab.outerHTML.substring(0, 150)}`);
    });

    const uls = doc.querySelectorAll('ul');
    console.log(`\nFound ${uls.length} <ul> elements:`);
    uls.forEach((ul, i) => {
        console.log(`[UL ${i}]: class="${ul.className}" id="${ul.id}" children=${ul.children.length}`);
        if (ul.children.length > 0) {
            console.log(`  First child: ${ul.children[0].outerHTML.substring(0, 200)}`);
        }
    });

    console.log("\nDone.");
    process.exit(0);
}, 6000);
