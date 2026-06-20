const bytes = Buffer.from("ChEVEkVbSUlXUAtZCQBTGUQWHw==", "base64");
const key = "beab6aff49f8ee874a0e6342f9669de3";

// Try standard XOR byte-by-byte
let decoded = "";
for (let i = 0; i < bytes.length; i++) {
    decoded += String.fromCharCode(bytes[i] ^ key.charCodeAt(i % key.length));
}
console.log("XOR key.charCodeAt(i):", decoded);

// Try XORing with key bytes (hex parsed)
const keyBytes = [];
for (let i = 0; i < key.length; i += 2) {
    keyBytes.push(parseInt(key.substring(i, i + 2), 16));
}
let decodedHex = "";
for (let i = 0; i < bytes.length; i++) {
    decodedHex += String.fromCharCode(bytes[i] ^ keyBytes[i % keyBytes.length]);
}
console.log("XOR hex bytes:", decodedHex);
