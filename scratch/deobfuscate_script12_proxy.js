const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;
const vm = require('vm');

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);
const doc = dom.window.document;

const scripts = doc.querySelectorAll('script');
const script12Content = scripts[12].textContent;

// Create a recursive proxy that returns itself on get or call
const createRecursiveProxy = (name) => {
    const handler = {
        get: (target, prop) => {
            // Special cases to prevent infinite recursion on standard JS properties
            if (prop === 'then' || prop === 'constructor' || prop === 'prototype') {
                return undefined;
            }
            if (prop === 'toString' || prop === Symbol.toStringTag) {
                return () => name;
            }
            return createRecursiveProxy(`${name}.${prop.toString()}`);
        },
        set: (target, prop, val) => {
            return true;
        },
        apply: (target, thisArg, args) => {
            return createRecursiveProxy(`${name}()`);
        }
    };
    // Return a function proxy so it can be called
    return new Proxy(function() {}, handler);
};

const sandbox = {
    console: console,
    window: {},
    document: createRecursiveProxy('document'),
    navigator: createRecursiveProxy('navigator'),
    location: createRecursiveProxy('location'),
    setTimeout: setTimeout,
    setInterval: setInterval,
    MutationObserver: function(callback) {
        return {
            observe: (node, options) => {
                console.log("[Bypass] MutationObserver.observe called!");
            },
            disconnect: () => {}
        };
    }
};
sandbox.window = sandbox;

vm.createContext(sandbox);

console.log("Evaluating Script 12 with proxy context...");
try {
    vm.runInContext(script12Content, sandbox);
    console.log("Script evaluated successfully.");
    
    // Now let's try to decrypt strings
    // We saw the decryption function is PwTT7pE (which uses index)
    const decrypter = sandbox.PwTT7pE || sandbox.xqtODv;
    if (decrypter) {
        console.log("\nDecrypted Script 12 strings:");
        for (let i = 0; i < 200; i++) {
            try {
                const val = decrypter(i);
                if (val && typeof val === 'string' && val.length > 0) {
                    console.log(`[${i} (0x${i.toString(16)})]: ${val}`);
                }
            } catch(e) {
                // ignore
            }
        }
    } else {
        console.log("Decrypter function not found in sandbox. Sandbox keys:", Object.keys(sandbox));
    }
} catch(e) {
    console.error("Error running script:", e);
}
