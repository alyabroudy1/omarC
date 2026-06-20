const fs = require('fs');

const html = fs.readFileSync('temp_yalla.html', 'utf8');

// Find all matches with class AY_Match
const matchBlockRegex = /<div class=["']AY_Match\s+([^"']+)["']>([\s\S]*?)<\/div>\s*<\/div>/g;
let matchBlock;
let count = 0;

console.log("=== Matches in temp_yalla.html ===");
while ((matchBlock = matchBlockRegex.exec(html)) !== null) {
    count++;
    const status = matchBlock[1];
    const content = matchBlock[2];

    const teams = [];
    const teamRegex = /<div class=['"]TM_Name['"]>([^<]+)<\/div>/g;
    let teamMatch;
    while ((teamMatch = teamRegex.exec(content)) !== null) {
        teams.push(teamMatch[1].trim());
    }

    const timeRegex = /<span class=['"]MT_Time['"]>([^<]+)<\/span>/;
    const timeMatch = content.match(timeRegex);
    const time = timeMatch ? timeMatch[1].trim() : "N/A";

    const resultRegex = /<span class=["']MT_Result["']>([\s\S]*?)<\/span>/;
    const resultMatch = content.match(resultRegex);
    let result = "VS";
    if (resultMatch) {
        const goals = resultMatch[1].replace(/<[^>]+>/g, '').replace(/\s+/g, '').trim();
        if (goals) result = goals;
    }

    const linkRegex = /<a href=["']([^"']+)["'] title=["']([^"']+)["']/;
    const linkMatch = content.match(linkRegex);
    const href = linkMatch ? linkMatch[1] : "N/A";
    const title = linkMatch ? linkMatch[2] : "N/A";

    console.log(`Match #${count}:`);
    console.log(`  Teams: ${teams.join(' vs ')}`);
    console.log(`  Time: ${time}`);
    console.log(`  Result/Status: ${result} [${status}]`);
    console.log(`  Link href: ${href}`);
    console.log(`  Link title: ${title}`);
    console.log("------------------------");
}
console.log(`Total AY_Match blocks found: ${count}`);
