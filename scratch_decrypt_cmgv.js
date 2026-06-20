const fs = require('fs');
const jsCode = fs.readFileSync('temp_deobfuscated_clean.js', 'utf8');

const cmgvMatch = jsCode.match(/let cmgv=\[(.*?)\];/);
if (cmgvMatch) {
    const cmgvStr = cmgvMatch[1];
    // Evaluate the array
    const cmgv = eval('[' + cmgvStr + ']');
    
    const decoded = cmgv.map(str => {
        let res = '';
        for (let i = 0; i < str.length; i++) {
            res += String.fromCharCode(str.charCodeAt(i) ^ 15);
        }
        return res;
    });
    
    fs.writeFileSync('decoded_cmgv.json', JSON.stringify(decoded, null, 2));
    console.log("Decoded cmgv saved to decoded_cmgv.json");
} else {
    console.log("Could not find cmgv array");
}
