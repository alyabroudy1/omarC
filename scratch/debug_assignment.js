const fs = require('fs');

const html = fs.readFileSync('cimanow_watch_noon.html', 'utf8');

const splitMatch = /var\s+_parts\s*=\s*(\w+)\.split/.exec(html);
if (!splitMatch) {
    console.error("Could not find split variable name");
    process.exit(1);
}
const varName = splitMatch[1];
console.log("Variable name:", varName);

const varRegex = new RegExp(`var\\s+${varName}\\s*=\\s*([\\s\\S]*?);`);
const varMatch = varRegex.exec(html);
if (!varMatch) {
    console.error(`Could not find definition of ${varName}`);
    process.exit(1);
}
const assignmentContent = varMatch[1];
console.log("Assignment content length:", assignmentContent.length);

// Print first 200 and last 200 characters of assignment
console.log("\nFirst 200 chars:");
console.log(assignmentContent.substring(0, 200));

console.log("\nLast 200 chars:");
console.log(assignmentContent.substring(assignmentContent.length - 200));

// Let's count how many times the tilde '~' occurs in the assignment content
const tildeCount = (assignmentContent.match(/~/g) || []).length;
console.log("\nNumber of tildes '~' in assignment content:", tildeCount);
