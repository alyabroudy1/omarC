const fs = require('fs');
const jsdom = require('jsdom');
const html = fs.readFileSync('temp_watch_guy.html', 'utf8');

// Extract all inline script bodies
const scriptRegex = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
let m;
let combinedJsCode = '';
while((m = scriptRegex.exec(html)) !== null) {
    if(!m[0].includes('src=')) {
        combinedJsCode += 'try { \n' + m[1] + '\n } catch(e) {}\n';
    }
}

const scriptToRun = `
var __cimanow_written = "";
document.write = function(str) { __cimanow_written += str; };
document.open = function() {};
document.close = function() {};

var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
window.atob = function(input) {
    var str = String(input).replace(/=+$/, '');
    var output = '';
    for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
        buffer = chars.indexOf(buffer);
    }
    return output;
};

// Prevent anti-bot redirects
window.location.replace = function() {};
window.location.assign = function() {};

` + combinedJsCode;

const dom = new jsdom.JSDOM(`<!DOCTYPE html><html><head></head><body></body></html>`, { runScripts: "dangerously" });
try {
    dom.window.eval(scriptToRun);
    const result = dom.window.__cimanow_written;
    fs.writeFileSync('cimanow_guy_decoded.html', result);
    console.log("Decoded size: " + result.length);
    const ulBtns = result.match(/<ul class=["']btns["']>([\s\S]*?)<\/ul>/i);
    if (ulBtns) {
        console.log("Buttons HTML:\n" + ulBtns[1]);
    } else {
        console.log("No ul.btns found");
    }
} catch(e) {
    console.log("Error evaluating: ", e);
}
