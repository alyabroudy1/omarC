const fs = require('fs');
const jsdom = require('jsdom');

// Forward JSDOM console to Node console
const virtualConsole = new jsdom.VirtualConsole();
virtualConsole.on("log", (...args) => console.log("[JSDOM LOG]", ...args));
virtualConsole.on("error", (...args) => console.error("[JSDOM ERR]", ...args));
virtualConsole.on("jsdomError", (...args) => console.error("[JSDOM CRITICAL]", ...args));

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

// Define jQuery mock function
const jQueryMock = function(selector) {
    console.log(`[Mock jQuery] called with selector: ${selector}`);
    const el = {
        on: function(event, subSelector, callback) {
            console.log(`[Mock jQuery] .on() registered for event: ${event}`);
            console.log(`[Mock jQuery] subSelector: ${subSelector}`);
            if (typeof subSelector === 'function') {
                callback = subSelector;
                subSelector = null;
            }
            if (subSelector && subSelector.includes('lalaplay_player_option')) {
                console.log("[Mock jQuery] => Found player option handler!");
                window.triggerPlayerClick = callback;
            }
            return el;
        },
        remove: () => {},
        html: (htmlContent) => {
            console.log("=== Intercepted Player IFrame HTML ===");
            console.log(htmlContent);
        },
        data: (key) => {
            if (key === 'type') return 'Cloud_V3';
            return null;
        }
    };
    return el;
};

// Set up both window and Node globals
window.jQuery = window.$ = jQueryMock;
global.jQuery = global.$ = jQueryMock;

// Set up other globals that the script expects
window.dtAjax = {
    post_link: "https://m.cimaleek.pw/movies/venom-633705/"
};
global.dtAjax = window.dtAjax;

// Load ajax-5.js
const ajax5Code = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = ajax5Code.split(/\r?\n/);
console.log("Total lines in ajax-5.js:", lines.length);
for (let i = 0; i < lines.length; i++) {
    console.log(`Line ${i} length: ${lines[i].length}`);
}

const playerCode = lines[1]; // Let's check line 1
if (!playerCode) {
    console.error("playerCode is undefined! Cannot run eval.");
    process.exit(1);
}
console.log("playerCode snippet:", playerCode.substring(0, 150));

// Evaluate only the player code inside the JSDOM context
try {
    window.eval(playerCode);
    console.log("Player code evaluated successfully.");

    // Intercept $.ajax
    window.jQuery.ajax = global.jQuery.ajax = (options) => {
        console.log("$.ajax called with URL:", options.url);
        // Simulate response
        const ajaxResponse = {
            a: "b3RuMwNjfLrkSz1ZTCGE==hOoFYn",
            b: [[8,14],[6,9],[7,13],[8,13]],
            c: "8c2f1fe5f4dd89065e9ca13710fe8d71",
            d: 1
        };
        options.success(ajaxResponse);
    };

    // Trigger the click handler
    if (window.triggerPlayerClick) {
        console.log("Triggering player option click...");
        // Call it in jQuery format where this is the element
        const mockBtn = window.jQuery('.lalaplay_player_option');
        window.triggerPlayerClick.call(mockBtn);
    } else {
        console.log("Click handler callback not found.");
    }
} catch (e) {
    console.error("Error running script:", e);
}
