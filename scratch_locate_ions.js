const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

let index = 0;
while (true) {
    index = line2.indexOf('IOns', index);
    if (index === -1) break;
    console.log(`Found 'IOns' at index ${index}`);
    console.log("Context:", line2.substring(index - 100, index + 300));
    
    // Let's trace backwards to find where it is defined, e.g. "function IOns" or "var IOns"
    let functionDefIdx = line2.lastIndexOf('function IOns', index);
    if (functionDefIdx !== -1) {
        console.log(`Found 'function IOns' at index ${functionDefIdx}:`);
        console.log("Definition:", line2.substring(functionDefIdx, functionDefIdx + 400));
    }
    let varDefIdx = line2.lastIndexOf('IOns=', index);
    if (varDefIdx !== -1) {
        console.log(`Found 'IOns=' at index ${varDefIdx}:`);
        console.log("Definition:", line2.substring(varDefIdx - 50, varDefIdx + 400));
    }
    index += 4;
}

// Do the same for MdTq
console.log("\n=== Searching for MdTq ===");
let index2 = 0;
while (true) {
    index2 = line2.indexOf('MdTq', index2);
    if (index2 === -1) break;
    console.log(`Found 'MdTq' at index ${index2}`);
    console.log("Context:", line2.substring(index2 - 100, index2 + 300));
    
    let functionDefIdx = line2.lastIndexOf('function MdTq', index2);
    if (functionDefIdx !== -1) {
        console.log(`Found 'function MdTq' at index ${functionDefIdx}:`);
        console.log("Definition:", line2.substring(functionDefIdx, functionDefIdx + 400));
    }
    let varDefIdx = line2.lastIndexOf('MdTq=', index2);
    if (varDefIdx !== -1) {
        console.log(`Found 'MdTq=' at index ${varDefIdx}:`);
        console.log("Definition:", line2.substring(varDefIdx - 50, varDefIdx + 400));
    }
    index2 += 4;
}
