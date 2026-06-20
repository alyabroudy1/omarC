const fs = require('fs');
if (fs.existsSync('cimanow_watch.html')) {
    const html = fs.readFileSync('cimanow_watch.html', 'utf8');
    console.log("cimanow_watch.html size:", html.length);
    console.log("Includes tabcontent:", html.includes("tabcontent"));
    console.log("Includes data-index:", html.includes("data-index"));
    console.log("Includes core.php:", html.includes("core.php"));
    console.log("Includes iframe:", html.includes("iframe"));
} else {
    console.log("cimanow_watch.html does not exist.");
}
