const jsdom = require('jsdom');

const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[JSDOM LOG]", ...args));
virtualConsole.on("error", (...args) => console.error("[JSDOM ERR]", ...args));

const dom = new jsdom.JSDOM(`<!DOCTYPE html><html><body></body></html>`, {
    url: 'https://m.cimaleek.pw/',
    runScripts: 'dangerously',
    virtualConsole: virtualConsole
});

const window = dom.window;

// Set up jQuery mock
window.jQuery = window.$ = function(selector) {
    console.log("[JSDOM jQuery Mock] called with selector:", selector);
    return {
        on: (event, callback) => {
            console.log(`[JSDOM jQuery Mock] .on() registered for event: ${event}`);
        }
    };
};

try {
    window.eval(`
        console.log("JSDOM Check jQuery:", typeof window.jQuery);
        window.jQuery("document").on("ready", function() {
            console.log("Document ready inside JSDOM");
        });
    `);
} catch(e) {
    console.error("Eval error:", e);
}
