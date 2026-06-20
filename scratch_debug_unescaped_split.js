const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

const evalIndex = line2.indexOf('eval(');
const lastQuoteIndex = line2.lastIndexOf('")');
const stringLiteralContent = line2.substring(evalIndex + 5, lastQuoteIndex + 1);
const unescapedStringVal = eval(stringLiteralContent);

const payloadIdx = unescapedStringVal.indexOf('G%09%11%05%0B');
console.log("payloadIdx in unescapedStringVal:", payloadIdx);

if (payloadIdx !== -1) {
    console.log("Context before payload:", unescapedStringVal.substring(payloadIdx - 50, payloadIdx));
    console.log("Context after payload:", unescapedStringVal.substring(payloadIdx + 13, payloadIdx + 100));
}
