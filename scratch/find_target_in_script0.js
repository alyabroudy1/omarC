const fs = require('fs');
const code = fs.readFileSync('cimanow_script0.js', 'utf8');

// Find index of 'return rj$OkiqbwdpKXrZ=function'
const idx = code.indexOf('return rj$OkiqbwdpKXrZ=function');
console.log("Found function start at index:", idx);

// Let's print the last 300 characters before the end of function rj$OkiqbwdpKXrZ
// The function ends with: },rj$OkiqbwdpKXrZ(O$MgNYJ_Ath,BVPoQtB_J_by);}
const endIdx = code.indexOf('},rj$OkiqbwdpKXrZ(O$MgNYJ_Ath,BVPoQtB_J_by);}');
console.log("Found function end at index:", endIdx);

if (idx !== -1 && endIdx !== -1) {
    const chunk = code.substring(endIdx - 300, endIdx + 45);
    console.log("Chunk at the end:\n", chunk);
}
