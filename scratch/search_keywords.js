const fs = require('fs');
const html = fs.readFileSync('cimanow_final_chain.html', 'utf8');

const keywords = ['ajax', 'core.php', 'admin-ajax.php', 'iframe', 'player', 'video', 'source', 'watching', 'play', 'server', 'freex2line', 'cimanow', 'post', 'get', 'fetch', 'XMLHttpRequest', 'stream'];

keywords.forEach(kw => {
    const regex = new RegExp(kw, 'gi');
    const matches = html.match(regex);
    console.log(`Keyword "${kw}": ${matches ? matches.length : 0} matches`);
});
