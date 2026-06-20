const fs = require('fs');

if (!fs.existsSync('temp_watch_guy.html')) {
    console.error("temp_watch_guy.html does not exist");
    process.exit(1);
}

const guy = fs.readFileSync('temp_watch_guy.html', 'utf8');
console.log("temp_watch_guy.html length:", guy.length);

const lines = guy.split('\n');
console.log("temp_watch_guy.html lines:", lines.length);

// Check if there are script tags
const withoutScript = guy.replace(/<script[\s\S]*?<\/script>/gi, '');
console.log("Length without scripts:", withoutScript.length);
if (withoutScript.length > 100) {
    console.log("Preview without script:");
    console.log(withoutScript.substring(0, 500));
}
