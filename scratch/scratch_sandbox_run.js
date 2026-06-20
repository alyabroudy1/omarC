const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

// Set up JSDOM
const dom = new JSDOM(`<!DOCTYPE html><html><body>
<div id="watch"></div>
<div id="download"></div>
<div id="episodes"></div>
</body></html>`, {
    url: 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/watching/',
    referrer: 'https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d9%86%d9%88%d9%86-%d8%a7%d9%84%d9%86%d8%b3%d9%88%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a/'
});

const window = dom.window;
const document = window.document;

// Mock jQuery
const mockJQuery = function(selector) {
    const el = {
        length: 1,
        ready: function(callback) {
            console.log("[jQuery] .ready callback registered.");
            try { callback(); } catch(e) { console.error("Error in ready:", e); }
            return el;
        },
        on: function(event, callback) {
            console.log(`[jQuery] Event registered: ${event} on ${selector}`);
            return el;
        },
        click: function(callback) {
            console.log(`[jQuery] Click registered on ${selector}`);
            return el;
        },
        removeClass: function(cls) { return el; },
        addClass: function(cls) { return el; },
        each: function(callback) {
            callback.call({ id: 'dummy' });
            return el;
        },
        find: function(sel) { return mockJQuery(sel); },
        attr: function(name, value) {
            if (value === undefined) {
                if (name === 'href') return 'https://cimanow.cc/';
                return '128476'; // data-id, etc.
            }
            return el;
        },
        text: function() { return '720p 500 ميجا'; },
        html: function() { return ''; },
        empty: function() { return el; },
        append: function() { return el; },
        remove: function() { return el; },
        trigger: function() { return el; },
        owlCarousel: function() { return el; }
    };
    return el;
};

mockJQuery.ready = function(callback) {
    console.log("[jQuery] mockJQuery.ready registered.");
    try { callback(); } catch(e) { console.error("Error in ready:", e); }
};


mockJQuery.mouseup = function(callback) { return mockJQuery; };
mockJQuery.ajax = function(options) {
    console.log("[jQuery.ajax] INTERCEPTED AJAX CALL:");
    console.log(JSON.stringify(options, null, 2));
};
mockJQuery.cookie = function() { return 'dummy_cookie'; };

global.window = window;
global.document = document;
global.navigator = window.navigator;
global.$ = mockJQuery;
global.jQuery = mockJQuery;
global.isSmartTV = function() { return false; };

// Load cimanow_script.js
const scriptCode = fs.readFileSync('cimanow_script.js', 'utf8');

console.log("Running nbm_script.js inside sandbox...");
try {
    eval(scriptCode);
    console.log("Script execution completed.");
} catch(e) {
    console.error("Error executing nbm_script.js:", e);
}
