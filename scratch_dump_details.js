const fs = require('fs');
const jsdom = require('jsdom');

console.log("=== PARSING MOVIE DETAILS ===");
const movieHtml = fs.readFileSync('temp_movie.html', 'utf8');
const movieDom = new jsdom.JSDOM(movieHtml);
const movieDoc = movieDom.window.document;

// Log title
const h1 = movieDoc.querySelector('h1');
console.log("H1 text:", h1 ? h1.textContent.trim() : 'No H1');

// Find all links to /watch/
const watchLinks = [];
movieDoc.querySelectorAll('a').forEach(a => {
    const href = a.getAttribute('href') || '';
    if (href.includes('/watch/')) {
        watchLinks.push({ text: a.textContent.trim(), href: href });
    }
});
console.log("Links containing '/watch/':", watchLinks);

// Let's find any button/link that is for watching or downloading
const allBtns = [];
movieDoc.querySelectorAll('a, button, div.btn').forEach(el => {
    const classList = el.className || '';
    const text = el.textContent.trim();
    if (classList.includes('btn') || classList.includes('watch') || text.includes('مشاهدة') || text.includes('تحميل')) {
        allBtns.push({ tag: el.tagName, class: classList, text: text, href: el.getAttribute('href') });
    }
});
console.log("Watch/Download Buttons:", allBtns.slice(0, 20));

// Find plot/description - look for a large block of text or standard classes like .story or .plot
const paragraphs = [];
movieDoc.querySelectorAll('p, div.story, div.plot, div.desc, div.description').forEach(el => {
    const text = el.textContent.trim();
    if (text.length > 20) {
        paragraphs.push({ tag: el.tagName, class: el.className, text: text.substring(0, 150) });
    }
});
console.log("Potential plot elements:", paragraphs.slice(0, 10));

// Let's find year
const yearElements = [];
movieDoc.querySelectorAll('span, li, div, p').forEach(el => {
    const text = el.textContent.trim();
    if (text.match(/^(19|20)\d{2}$/) || text.includes('السنة') || text.includes('سنة')) {
        yearElements.push({ tag: el.tagName, class: el.className, text: text });
    }
});
console.log("Potential year elements:", yearElements.slice(0, 10));


console.log("\n=== PARSING SERIES DETAILS ===");
const seriesHtml = fs.readFileSync('temp_series.html', 'utf8');
const seriesDom = new jsdom.JSDOM(seriesHtml);
const seriesDoc = seriesDom.window.document;

const sh1 = seriesDoc.querySelector('h1');
console.log("Series H1 text:", sh1 ? sh1.textContent.trim() : 'No H1');

// Look for season list and episode list in TV Series
// In many wordpress themes, episodes list is a list of links or boxes
console.log("All links in Series Page:");
const seriesLinks = [];
seriesDoc.querySelectorAll('a').forEach(a => {
    const href = a.getAttribute('href') || '';
    const text = a.textContent.trim();
    const classList = a.className || '';
    // Look for links containing episode numbers or season keywords
    if (href.includes('/episode/') || href.includes('-episode-') || href.includes('-season-') || text.includes('حلقة') || text.includes('الحلقة')) {
        seriesLinks.push({ text, href, class: classList });
    }
});
console.log("Filtered series links:", seriesLinks.slice(0, 30));

// If no links found, let's dump the HTML of some containers
const containers = [];
seriesDoc.querySelectorAll('.seasons, .episodes, .list-episodes, .list-seasons, #seasons, #episodes, .List--Seasons--Episodes, .seasons-list, .episodes-list').forEach(el => {
    containers.push({ tag: el.tagName, class: el.className, id: el.id, html: el.innerHTML.substring(0, 300) });
});
console.log("Containers with season/episode classes:", containers);
