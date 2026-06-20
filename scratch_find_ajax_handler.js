const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Find any occurrences of "140", "141", "142", "143", "144", "145", "153", "154", "155" inside function calls in line 2.
// E.g., UCBv(144) or oUSt(140) etc.
// We can use a regex to match: [a-zA-Z0-9]+\((140|141|142|143|144|145|146|147|148|149|150|151|152|153|154|155)\)
const regex = /[a-zA-Z0-9]+\((140|141|142|143|144|145|146|147|148|149|150|151|152|153|154|155)\)/g;

let match;
while ((match = regex.exec(line2)) !== null) {
    const matchedStr = match[0];
    const index = match.index;
    console.log(`Found match '${matchedStr}' at index ${index}:`);
    console.log("Context:", line2.substring(index - 100, index + 350));
}
