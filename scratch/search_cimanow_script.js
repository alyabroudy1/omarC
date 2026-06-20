const fs = require('fs');

const js = fs.readFileSync('cimanow_script.js', 'utf8');
console.log("cimanow_script.js size:", js.length);

const keywords = ['ajax', 'post', 'json', 'watching', 'download', 'wp-admin', 'admin-ajax.php', 'api.cimanow.online', 'https://api.cimanow'];

keywords.forEach(keyword => {
    let index = 0;
    let count = 0;
    console.log(`\n=== Keyword: "${keyword}" ===`);
    while (true) {
        index = js.toLowerCase().indexOf(keyword.toLowerCase(), index);
        if (index === -1) break;
        console.log(`[Match ${count}] at index ${index}:`);
        console.log(js.substring(index - 100, index + 300));
        console.log("--------------------------------------------------");
        count++;
        index += keyword.length;
        if (count >= 10) {
            console.log("... Truncated after 10 matches");
            break;
        }
    }
});
