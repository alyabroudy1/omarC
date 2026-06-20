const fs = require('fs');

// Read cimanow_script0.js and remove the window.addEventListener wrapper
let code = fs.readFileSync('cimanow_script0.js', 'utf8');
code = code.replace(/^window\.addEventListener\("load",\s*function\(\)\s*\{/, '');
code = code.replace(/\}\);$/, '');

// Intercept strings inside the inner rj$OkiqbwdpKXrZ function
const target = "return!tdSUavCZX_CWmVe_cZsirb?(rj$OkiqbwdpKXrZ['BOZiAg']===undefined&&(rj$OkiqbwdpKXrZ['BOZiAg']=!![]),RHFR__l=rj$OkiqbwdpKXrZ['PFNcdA'](RHFR__l),O$MgNYJ_Ath[psMYLyvUahwVJWrT]=RHFR__l):RHFR__l=tdSUavCZX_CWmVe_cZsirb,RHFR__l;";
fs.writeFileSync('scratch/all_decrypted_strings.txt', '');
if (code.includes(target)) {
    code = code.replace(target, `
const _res = (!tdSUavCZX_CWmVe_cZsirb?(rj$OkiqbwdpKXrZ['BOZiAg']===undefined&&(rj$OkiqbwdpKXrZ['BOZiAg']=!![]),RHFR__l=rj$OkiqbwdpKXrZ['PFNcdA'](RHFR__l),O$MgNYJ_Ath[psMYLyvUahwVJWrT]=RHFR__l):RHFR__l=tdSUavCZX_CWmVe_cZsirb,RHFR__l);
if (typeof _res === 'string' && _res.length > 0) {
    fs.appendFileSync('scratch/all_decrypted_strings.txt', _res + '\\n');
}
return _res;
`);
    console.log("Successfully patched decrypter hook!");
} else {
    console.error("Target not found!");
}

// Create mocked window/document objects
const mockedDocument = {
    readyState: 'loading',
    body: {
        appendChild: (child) => {
            console.log("document.body.appendChild called with:", child);
        }
    },
    createElement: (tag) => {
        return { style: {}, appendChild: () => {}, setAttribute: () => {}, classList: { add: () => {} } };
    },
    getElementsByTagName: (tag) => {
        return [{ appendChild: () => {} }];
    }
};

const listeners = [];
const mockedWindow = {
    addEventListener: (event, cb) => {
        console.log("window.addEventListener:", event);
        if (event === 'load') {
            listeners.push(cb);
        }
    },
    removeEventListener: (event, cb) => {
        console.log("window.removeEventListener:", event);
    }
};

// Evaluate the code
const evalInContext = new Function('window', 'document', 'Image', 'requestAnimationFrame', 'fs', code);
try {
    const mockedImage = function() { return { style: {} }; };
    const mockedRaf = (cb) => { setTimeout(cb, 10); };
    evalInContext(mockedWindow, mockedDocument, mockedImage, mockedRaf, fs);
    console.log("Triggering load event listeners...");
    listeners.forEach(cb => cb());
} catch (e) {
    console.error("Execution error:", e);
}
