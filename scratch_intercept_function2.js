const fs = require('fs');

global.intercept_Function = function(...args) {
    if (args.length > 0) {
        const code = args[args.length - 1];
        if (code && typeof code === 'string') {
            fs.writeFileSync('dumped_aqhe.js', code);
            console.log('Intercepted Function constructor! Wrote to dumped_aqhe.js');
        }
    }
    return global.Function.apply(this, args);
};

global.window = global;
global.document = {
    addEventListener: () => {},
    getElementById: () => null,
    querySelector: () => null,
    createElement: () => ({})
};

try {
    let jsCode = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');
    // Replace the exact Function(...) call
    jsCode = jsCode.replace(/Function\(/g, 'intercept_Function(');
    eval(jsCode);
} catch (e) {
    console.error('Eval error:', e);
}
