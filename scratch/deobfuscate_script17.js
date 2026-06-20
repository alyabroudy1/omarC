const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;
const vm = require('vm');

const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');
const dom = new JSDOM(html);

const scripts = doc = dom.window.document.querySelectorAll('script');
const script17Content = scripts[17].textContent;

// Create recursive proxy
const createRecursiveProxy = (name) => {
    const handler = {
        get: (target, prop) => {
            if (prop === 'then' || prop === 'constructor' || prop === 'prototype') return undefined;
            if (prop === 'toString' || prop === Symbol.toStringTag) return () => name;
            return createRecursiveProxy(`${name}.${prop.toString()}`);
        },
        set: (target, prop, val) => true,
        apply: (target, thisArg, args) => createRecursiveProxy(`${name}()`)
    };
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
    MutationObserver: function() {
        return { observe: () => {}, disconnect: () => {} };
    }
};
sandbox.window = sandbox;

vm.createContext(sandbox);

console.log("Evaluating Script 17...");
try {
    vm.runInContext(script17Content, sandbox);
    console.log("Script 17 evaluated.");
    
    // Decrypter function in Script 17 is UXdJ$ZFo (or similar from content snippet: UXdJ$ZFo(0x1a3))
    const keys = Object.keys(sandbox);
    console.log("Keys in sandbox:", keys);
    
    // Find decrypter function by name
    const decrypterName = keys.find(k => k.startsWith('UXdJ') || k.includes('ZFo') || sandbox[k] && typeof sandbox[k] === 'function');
    console.log("Found decrypter function name:", decrypterName);
    
    if (decrypterName) {
        const decrypter = sandbox[decrypterName];
        console.log("\nDecrypted Script 17 strings:");
        for (let i = 0x100; i < 0x200; i++) {
            try {
                const val = decrypter(i);
                if (val) console.log(`[${i} (0x${i.toString(16)})]: ${val}`);
            } catch(e) {}
        }
    }
} catch(e) {
    console.error("Error running script:", e);
}
