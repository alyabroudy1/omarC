const fs = require('fs');
const vm = require('vm');

const code = fs.readFileSync('tako.js', 'utf8');

// Set up a sandbox
const sandbox = {
    console: {
        log: (...args) => console.log("[VM Log]:", ...args),
        error: (...args) => console.error("[VM Err]:", ...args)
    },
    setTimeout: setTimeout,
    clearTimeout: clearTimeout,
    setInterval: setInterval,
    clearInterval: clearInterval,
    window: {},
    document: {
        currentScript: {
            remove: () => {}
        },
        createElement: (name) => {
            console.log(`[VM Document] createElement: ${name}`);
            return {
                style: {},
                appendChild: () => {},
                remove: () => {}
            };
        },
        head: {
            appendChild: (el) => {
                console.log(`[VM Document] head appendChild:`, el);
            }
        },
        getElementById: (id) => {
            console.log(`[VM Document] getElementById: ${id}`);
            return {
                textContent: "Dummy text content to pass length check"
            };
        }
    },
    fetch: async (url, options) => {
        console.log(`[VM Fetch] URL: ${url}`, options);
        return {
            status: 200,
            ok: true,
            then: (callback) => {
                console.log("[VM Fetch] then called");
                callback();
                return {
                    then: (cb) => { cb(); }
                };
            }
        };
    }
};

sandbox.window.location = {
    href: 'https://rm.freex2line.online/2020/02/blog-post.html/'
};
sandbox.window.addEventListener = (event, callback) => {
    console.log(`[VM Window] addEventListener: ${event}`);
    if (event === 'load') {
        setTimeout(callback, 100);
    }
};

vm.createContext(sandbox);

console.log("Running tako.js in VM...");
try {
    vm.runInContext(code, sandbox);
} catch (e) {
    console.error("Error running code:", e);
}

// Dump sandbox keys
console.log("\nSandbox keys:");
console.log(Object.keys(sandbox));

// Let's print out the decoded strings if we can access them
if (sandbox.Udqvfy) {
    console.log("\nDecrypted Udqvfy strings:");
    for (let i = 0; i < sandbox.Udqvfy.length; i++) {
        try {
            const val = sandbox.TlmesL5(i);
            console.log(`[${i}]: ${val}`);
        } catch (e) {
            // ignore
        }
    }
}
