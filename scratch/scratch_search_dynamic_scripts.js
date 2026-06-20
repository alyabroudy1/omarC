const fs = require('fs');

const files = [
    'scratch_full_script_11.js',
    'scratch_full_script_12.js',
    'scratch_full_script_13.js',
    'scratch_full_script_14.js',
    'scratch_full_script_15.js',
    'scratch_full_script_16.js',
    'scratch_full_script_18.js'
];

const keywords = ['ajax', 'post', 'get', 'json', 'wp-json', 'api', 'http', 'action', 'url', 'profile', 'watchlist', 'auth', 'token'];

files.forEach(file => {
    if (!fs.existsSync(file)) return;
    const js = fs.readFileSync(file, 'utf8');
    console.log(`\n=== Scanning: ${file} (size: ${js.length} bytes) ===`);
    
    keywords.forEach(keyword => {
        let index = 0;
        let count = 0;
        while (true) {
            index = js.toLowerCase().indexOf(keyword.toLowerCase(), index);
            if (index === -1) break;
            console.log(`  [Match ${count}] Keyword "${keyword}" at index ${index}:`);
            console.log("    " + js.substring(index - 60, index + 140).replace(/\r?\n/g, ' '));
            count++;
            index += keyword.length;
            if (count >= 5) break;
        }
    });
});
