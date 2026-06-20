const fs = require('fs');

const originalFunction = global.Function;
global.Function = function(...args) {
    if (args.length > 0) {
        const code = args[args.length - 1];
        if (code && typeof code === 'string' && code.includes('return')) {
            // Probably the unpacked code
            fs.writeFileSync('dumped_aqhe.js', code);
            console.log('Intercepted Function constructor! Wrote to dumped_aqhe.js');
        }
    }
    return originalFunction.apply(this, args);
};

// Also polyfill window and document
global.window = global;
global.document = {
    addEventListener: () => {},
    getElementById: () => null,
    querySelector: () => null,
    createElement: () => ({})
};

try {
    const jsCode = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');
    eval(jsCode);
} catch (e) {
    console.error('Eval error:', e);
}
