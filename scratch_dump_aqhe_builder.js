const fs = require('fs');
const jsCode = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

let modifiedCode = jsCode.replace(
    /const MdLA=obLu=Function\(AQhE\(UXxC\(\),oToC\(\),YCFF\(\),oncD\(\)\)\)\(\);/g,
    "const MdLA=obLu={}; const codeToEval = AQhE(UXxC(),oToC(),YCFF(),oncD()); require('fs').writeFileSync('dumped_aqhe.js', codeToEval);"
);

// We need to also expose the window/document so it doesn't crash if it tries to access them.
modifiedCode = `
global.window = global;
global.document = {
    addEventListener: () => {},
    getElementById: () => null,
    querySelector: () => null
};
` + modifiedCode;

fs.writeFileSync('scratch_dump_aqhe.js', modifiedCode);
