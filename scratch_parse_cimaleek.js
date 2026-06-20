const fs = require('fs');
const jsdom = require('jsdom');

const homeHtml = fs.readFileSync('temp_b5.html', 'utf8');
const dom = new jsdom.JSDOM(homeHtml);
const doc = dom.window.document;

const item = doc.querySelector('.posts_items .item, .swiper-slide .item, .item');
if (item) {
    console.log("=== ITEM HTML DUMP ===");
    console.log(item.outerHTML);
} else {
    console.log("No item found");
}

console.log("\n=== Checking details page / loading page elements ===");
// Since we don't have movie html yet, let's write a fetch for one movie details page and see its structure.
const https = require('https');
function fetchUrl(url) {
    return new Promise((resolve, reject) => {
        https.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }
        }, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => { resolve(data); });
        }).on('error', reject);
    });
}

async function getMovieDetail() {
    const html = await fetchUrl('https://m.cimaleek.pw/movies/venom-633705/');
    fs.writeFileSync('temp_movie.html', html);
    const movieDom = new jsdom.JSDOM(html);
    const movieDoc = movieDom.window.document;
    
    console.log("=== MOVIE DETAIL DUMP ===");
    const title = movieDoc.querySelector('.title, h1, .scontent h1');
    const desc = movieDoc.querySelector('.desc, .plot, .story, .PostContent p, .scontent p');
    const poster = movieDoc.querySelector('.poster img, .simages img');
    const meta = movieDoc.querySelector('.mepo, .meta, .scontent');
    
    console.log("Title tag/text:", title ? `${title.tagName}: ${title.textContent.trim()}` : 'none');
    console.log("Desc tag/text:", desc ? `${desc.tagName}: ${desc.textContent.trim().substring(0, 200)}` : 'none');
    console.log("Poster img src:", poster ? poster.getAttribute('src') || poster.getAttribute('data-src') : 'none');
    console.log("Meta content:", meta ? meta.outerHTML.substring(0, 1000) : 'none');
    
    // Check if there is an episode or seasons list on a TV series page
    console.log("\nFetching series detail page...");
    const seriesHtml = await fetchUrl('https://m.cimaleek.pw/series/the-penguin-491274/');
    fs.writeFileSync('temp_series.html', seriesHtml);
    const seriesDom = new jsdom.JSDOM(seriesHtml);
    const seriesDoc = seriesDom.window.document;
    
    console.log("=== SERIES DETAIL DUMP ===");
    const episodes = seriesDoc.querySelectorAll('.episodes-list a, .list-episodes a, a[href*="/episode/"], .item a[href*="/episode/"]');
    console.log(`Found ${episodes.length} episodes links`);
    let epCount = 0;
    for (let ep of episodes) {
        console.log(`Episode Link: ${ep.getAttribute('href')} | Text: ${ep.textContent.trim()}`);
        epCount++;
        if (epCount > 10) break;
    }
}

getMovieDetail();
