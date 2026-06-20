const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Find the index of `)("`
const splitIndex = line2.indexOf(')("');
console.log("splitIndex:", splitIndex);

if (splitIndex !== -1) {
    // The string inside eval starts at index 83 and goes up to splitIndex - 1 (since the closing quote is before `)`)
    // Wait, let's verify if there is a double quote before `)`
    console.log("Char before splitIndex:", line2[splitIndex - 1]);
    const ejaxString = line2.substring(83, splitIndex - 1); // exclude the quote
    console.log("ejaxString length:", ejaxString.length);
    console.log("ejaxString starts with:", ejaxString.substring(0, 100));
    console.log("ejaxString ends with:", ejaxString.substring(ejaxString.length - 100));

    // The argument starts after `)("`
    const argStart = splitIndex + 3;
    const argEnd = line2.indexOf('")', argStart);
    console.log("argEnd:", argEnd);
    const nzHp = line2.substring(argStart, argEnd);
    console.log("nzHp length:", nzHp.length);
    console.log("nzHp starts with:", nzHp.substring(0, 100));
}
