const fs = require('fs');
const html = fs.readFileSync('cimanow_watch_302.html', 'utf8');

function decrypt(html) {
    const cVarMatch = html.match(/var (_c\d+)\s*=\s*'([\s\S]*?)';/);
    if (!cVarMatch) {
        console.log("No _c var found");
        return null;
    }
    const obfuscatedData = cVarMatch[2].replace(/[\r\n\t'+\s]/g, "");
    
    const rMatch = html.match(/var _r\s*=\s*([0-9\s+]+);/);
    if (!rMatch) {
        console.log("No _r var found");
        return null;
    }
    const formula = rMatch[1];
    const rValue = formula.split("+").map(x => parseInt(x.trim())).reduce((a, b) => a + b, 0);
    console.log("rValue calculated:", rValue);

    const tokens = obfuscatedData.split("~");
    const outputBuffer = [];

    for (const token of tokens) {
        if (!token) continue;
        try {
            let padded = token;
            while (padded.length % 4 !== 0) {
                padded += "=";
            }
            const decodedStr = Buffer.from(padded, 'base64').toString('utf8');
            const digitsOnly = decodedStr.replace(/\D/g, "");
            if (!digitsOnly) continue;

            const num = parseInt(digitsOnly) - rValue;
            outputBuffer.push(num);
        } catch (e) {
            console.error("Error decoding token:", token, e);
        }
    }

    return Buffer.from(outputBuffer).toString('utf8');
}

const decrypted = decrypt(html);
if (decrypted) {
    fs.writeFileSync('cimanow_watch_decrypted_test.html', decrypted);
    console.log("Decrypted output size:", decrypted.length);
    console.log("First 500 chars of decrypted content:\n", decrypted.substring(0, 500));
} else {
    console.log("Decryption failed.");
}
