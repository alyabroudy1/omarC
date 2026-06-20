const fs = require('fs');
const html = fs.readFileSync('cimanow_episode.html', 'utf8');

console.log("Analyzing all links on the episode page...");

const aRegex = /<a\s+(?:[^>]*?\s+)?href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi;
let match;
let count = 0;
while ((match = aRegex.exec(html)) !== null) {
    const href = match[1];
    const text = match[2].replace(/<[^>]*>/g, '').trim();
    if (href.includes('cimanow') || href.startsWith('/') || href.includes('watching') || href.includes('watch') || text) {
        console.log(`Link #${count++}: href="${href}" text="${text.substring(0, 50)}"`);
    }
}

console.log("\nSearching for class names that might contain watch links:");
const classMatches = html.match(/class="([^"]+)"/g);
const classes = new Set(classMatches ? classMatches.map(c => c.substring(7, c.length-1)) : []);
const targetClasses = Array.from(classes).filter(c => c.includes('btn') || c.includes('play') || c.includes('watch') || c.includes('server') || c.includes('episode'));
console.log("Found classes:", targetClasses);
