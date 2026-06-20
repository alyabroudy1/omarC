const fs = require('fs');
const code = fs.readFileSync('c:\\Users\\alyab\\AndroidStudioProjects\\cloudstream-standard-v2\\omarC\\temp_deobfuscated_clean.js', 'utf8');

const onktIdx = code.indexOf('onkt');
console.log("onkt index:", onktIdx);
if (onktIdx !== -1) {
    console.log("onkt context:", code.substring(onktIdx - 100, onktIdx + 300));
}
