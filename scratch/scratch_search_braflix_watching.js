const fs = require('fs');

const js = fs.readFileSync('cimanow_decompiled/src/sources/com/braflix/Braflix.java', 'utf8');
console.log("Braflix.java size:", js.length);

const keywords = ['watching', 'loadLinks', 'cookie', 'wp-admin', 'admin-ajax'];

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
        if (count >= 10) break;
    }
});
