const fs = require('fs');

// Mock globals
global.jQuery = function() { return global.jQuery; };
global.jQuery.ready = function(fn) { fn(); };
global.window = { jQuery: global.jQuery };
global.document = {
    createElement: function() { return { setAttribute: () => {}, appendChild: () => {} }; },
    querySelectorAll: function() { return []; },
    querySelector: function() { return null; }
};
global.escape = encodeURIComponent;

const code = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');
let cleanCode = code.trim();
if (cleanCode.startsWith('(function(){')) cleanCode = cleanCode.substring(12);
if (cleanCode.endsWith('})()')) cleanCode = cleanCode.substring(0, cleanCode.length - 4);
if (cleanCode.endsWith('})')) cleanCode = cleanCode.substring(0, cleanCode.length - 2);

try {
    eval(cleanCode);
    console.log("Evaluation successful!");
    
    console.log("=== Extra AJAX Keys ===");
    console.log("Ahgw(195) =", k53x.Ahgw(195));
    console.log("UCBv(249) =", k53x.UCBv(249));
    console.log("oUSt(158) =", k53x.oUSt(158));
    console.log("Qtsu(159) =", k53x.Qtsu(159));
    console.log("waEv(152) =", k53x.waEv(152));
    console.log("UCBv(153) =", k53x.UCBv(153));
    console.log("Qz3v(154) =", k53x.Qz3v(154));
    console.log("Ahgw(155) =", k53x.Ahgw(155));
    console.log("YB5t(156) =", k53x.YB5t(156));
    console.log("w42t(157) =", k53x.w42t(157));
    console.log("oUSt(174) =", k53x.oUSt(174));
    console.log("oUSt(46) =", k53x.oUSt(46));
    console.log("Ahgw(131) =", k53x.Ahgw(131));
    console.log("Qz3v(34) =", k53x.Qz3v(34));
    console.log("YB5t(252) =", k53x.YB5t(252));
    console.log("w42t(253) =", k53x.w42t(253));
    console.log("oUSt(190) =", k53x.oUSt(190));
    console.log("YB5t(84) =", k53x.YB5t(84));
    console.log("oUSt(198) =", k53x.oUSt(198));
    console.log("Qtsu(199) =", k53x.Qtsu(199));
    console.log("waEv(184) =", k53x.waEv(184));
    console.log("UCBv(193) =", k53x.UCBv(193));
    console.log("Qtsu(183) =", k53x.Qtsu(183));
    console.log("w42t(13) =", k53x.w42t(13));
    console.log("Qz3v(194) =", k53x.Qz3v(194));
    
} catch (e) {
    console.error("Failed:", e);
}
