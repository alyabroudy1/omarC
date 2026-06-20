const fs = require('fs');
const vm = require('vm');

const js = fs.readFileSync('cimanow_script.js', 'utf8');

// Set up a mock DOM environment
const createRecursiveProxy = (name) => {
    const handler = {
        get: (target, prop) => {
            if (prop === 'then' || prop === 'constructor' || prop === 'prototype') return undefined;
            if (prop === 'toString' || prop === Symbol.toStringTag) return () => name;
            if (prop === Symbol.toPrimitive) return (hint) => {
                if (hint === 'number') return 1;
                return name;
            };
            if (prop === 'valueOf') return () => 1;
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
    setInterval: setInterval
};
sandbox.window = sandbox;

vm.createContext(sandbox);

console.log("Running cimanow_script.js in VM...");
try {
    vm.runInContext(js, sandbox);
    console.log("Execution completed. Checking global variables in sandbox...");
    
    const keys = Object.keys(sandbox).filter(k => k !== 'console' && k !== 'window' && k !== 'document' && k !== 'navigator' && k !== 'location' && k !== 'setTimeout' && k !== 'setInterval');
    console.log("Created keys:", keys);
    
    // Find functions in sandbox
    const functions = keys.filter(k => typeof sandbox[k] === 'function');
    console.log("Functions found:", functions);
    
    // Let's test each function by passing a hex value (e.g. 0x200) to see if it decodes strings
    functions.forEach(fnName => {
        const fn = sandbox[fnName];
        console.log(`\n=== Testing function ${fnName} ===`);
        let count = 0;
        const decodedStrings = [];
        for (let i = 0x0; i < 0x800; i++) {
            try {
                const val = fn(i);
                if (val && typeof val === 'string' && val.length > 1) {
                    decodedStrings.push(`[0x${i.toString(16)}]: ${val}`);
                    count++;
                }
            } catch(e) {}
        }
        console.log(`Function ${fnName} decoded ${count} strings.`);
        if (count > 0) {
            fs.writeFileSync(`decoded_${fnName}_strings.txt`, decodedStrings.join('\n'));
            console.log(`Saved to decoded_${fnName}_strings.txt`);
        }
    });

} catch (e) {
    console.error("Error executing script:", e);
}
