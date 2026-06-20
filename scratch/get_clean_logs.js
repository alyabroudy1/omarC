const fs = require('fs');
const jsdom = require('jsdom');

let debugLogs = [];
function log(...args) {
    const msg = args.map(a => {
        if (typeof a === 'object' && a !== null) {
            try { return JSON.stringify(a); } catch(e) { return String(a); }
        }
        return String(a);
    }).join(' ');
    debugLogs.push(msg);
    console.log(msg);
}

// Forward JSDOM console
const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => log("[JSDOM LOG]", ...args));
virtualConsole.on("error", (...args) => log("[JSDOM ERR]", ...args));
virtualConsole.on("jsdomError", (error) => {
    log("[JSDOM CRITICAL] Message:", error.message);
    log("[JSDOM CRITICAL] Stack:", error.stack);
    if (error.detail) {
        log("[JSDOM CRITICAL] Detail:", error.detail.message, error.detail.stack);
    }
});

// Create JSDOM window
const dom = new jsdom.JSDOM(`
<!DOCTYPE html>
<html>
<head></head>
<body>
    <div id="lalaplay_player_response"></div>
</body>
</html>
`, {
    url: 'https://m.cimaleek.pw/movies/venom-633705/watch/',
    runScripts: 'dangerously',
    virtualConsole: virtualConsole
});

const window = dom.window;

// Define helper function expected by the script
window.gID = function(id) {
    log(`[gID called] for id: ${id}`);
    return {
        setAttribute: () => {},
        appendChild: () => {},
        style: {},
        addEventListener: () => {}
    };
};
global.gID = window.gID;

// Define generateRandomString
window.generateRandomString = function(len) {
    log(`[generateRandomString called] with length: ${len}`);
    let result = '';
    const characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < len; i++) {
        result += characters.charAt(Math.floor(Math.random() * characters.length));
    }
    return result;
};
global.generateRandomString = window.generateRandomString;

// Define recursive Proxy to act as jQuery
const makeProxy = (name) => {
    const fn = function(...args) {
        log(`[Proxy ${name}] CALL with args:`, args);
        // If jQuery is called with a ready function, execute it
        if (name === '$' && args.length === 1 && typeof args[0] === 'function') {
            log(`[Proxy ${name}] ready callback detected, executing...`);
            try {
                args[0]();
            } catch(e) {
                log(`[Proxy ${name} ready callback error]:`, e.message, e.stack);
            }
            return jQueryProxy;
        }
        return makeProxy(`${name}()`);
    };
    
    return new Proxy(fn, {
        get(target, prop) {
            // Avoid issues with promise detection or console.log inspector
            if (prop === 'then' || prop === 'inspect' || typeof prop === 'symbol') {
                return undefined;
            }
            log(`[Proxy ${name}] GET prop: ${String(prop)}`);
            
            // Handle primitive conversions
            if (prop === 'length') return 1;
            if (prop === 'valueOf') return () => 1;
            if (prop === 'toString') return () => `[Proxy ${name}]`;
            
            // Intercept event listeners
            if (prop === 'on') {
                return function(event, subSelector, callback) {
                    log(`[Proxy ${name}.on] registered: event=${event}, subSelector=${subSelector}`);
                    if (typeof subSelector === 'function') {
                        callback = subSelector;
                        subSelector = null;
                    }
                    if (subSelector && subSelector.includes('lalaplay_player_option')) {
                        log("[Proxy] => Captured player option click handler!");
                        window.triggerPlayerClick = callback;
                    }
                    return makeProxy(`${name}.on()`);
                };
            }
            
            // Intercept AJAX
            if (prop === 'ajax') {
                return function(options) {
                    log("[Proxy $.ajax] called with options URL:", options.url);
                    log("[Proxy $.ajax] type:", options.type);
                    log("[Proxy $.ajax] data:", typeof options.data === 'object' ? JSON.stringify(options.data) : String(options.data));
                    log("[Proxy $.ajax] headers:", JSON.stringify(options.headers || {}));
                    if (options.success) {
                        log("[Proxy $.ajax] Simulating successful response...");
                        const ajaxResponse = {
                            a: "b3RuMwNjfLrkSz1ZTCGE==hOoFYn",
                            b: [[8,14],[6,9],[7,13],[8,13]],
                            c: "8c2f1fe5f4dd89065e9ca13710fe8d71",
                            d: 1
                        };
                        try {
                            options.success(ajaxResponse);
                        } catch(e) {
                            log("[Proxy $.ajax callback error]:", e.message, e.stack);
                        }
                    }
                };
            }
            
            // Standard jQuery mock data method
            if (prop === 'data') {
                return function(key) {
                    log(`[Proxy ${name}.data] called for key: ${key}`);
                    if (key === 'type') return 'Cloud_V3';
                    if (key === 'post') return '4354';
                    if (key === 'nume') return '1';
                    return null;
                };
            }
            
            if (prop === 'text') {
                return function() {
                    return "Cloud_V3";
                };
            }

            if (prop === 'hasClass') {
                return function(className) {
                    log(`[Proxy ${name}.hasClass] called for class: ${className}, returning false`);
                    return false;
                };
            }

            return makeProxy(`${name}.${String(prop)}`);
        }
    });
};

const jQueryProxy = makeProxy('$');

// Set up window/Node globals
window.jQuery = window.$ = jQueryProxy;
global.jQuery = global.$ = jQueryProxy;

// Set up other globals
window.dtAjax = {
    post_link: "https://m.cimaleek.pw/movies/venom-633705/",
    post_id: "4354",
    player_api: "/wp-json/lalaplayer/v2/",
    site_url: "https://m.cimaleek.pw",
    play_method: "wp_json",
    playeradstime: 0
};
global.dtAjax = window.dtAjax;

window.dtGonza = {
    livesearchactive: false,
    autoplayer: null
};
global.dtGonza = window.dtGonza;

// Load ajax-5.js and split by line
const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split(/\r?\n/);
const playerCode = lines[1]; // Second line contains the player code

try {
    window.eval(playerCode);
    log("Player code evaluated successfully.");

    // Trigger the click handler
    if (window.triggerPlayerClick) {
        log("Triggering player option click...");
        // Call it in jQuery format where 'this' has our mock data attributes
        const mockBtn = window.jQuery('.lalaplay_player_option');
        window.triggerPlayerClick.call(mockBtn);
    } else {
        log("Click handler callback not found.");
    }
} catch (e) {
    log("Error running script:", e.message, e.stack);
}

// Wait for setTimeout to execute the decrypter
setTimeout(() => {
    fs.writeFileSync('c:\\Users\\alyab\\AndroidStudioProjects\\cloudstream-standard-v2\\omarC\\CimaLeek_JSDOM_debug.txt', debugLogs.join('\n'), 'utf8');
    log("Debug logs saved to CimaLeek_JSDOM_debug.txt");
    process.exit(0);
}, 2500);
