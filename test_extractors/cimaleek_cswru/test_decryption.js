function MdTq(MxGr, ozJr, IuAr) {
    let kwDr = MxGr;
    // Loop backwards through ozJr
    for (let i = ozJr.length - 1; i >= 0; i--) {
        const Aoor = ozJr[i][0];
        const cqrr = ozJr[i][1];
        const start = Aoor - IuAr;
        console.log(`Slice indices: start=${start}, end=${cqrr}`);
        kwDr = kwDr.slice(0, start) + kwDr.slice(cqrr);
    }
    return kwDr;
}

function decrypt(encryptedText, b, c, IuAr) {
    const cleaned = MdTq(encryptedText, b, IuAr);
    console.log("Cleaned (Base64 encoded):", cleaned);
    
    // Base64 decode to char/byte array
    const decoded = Buffer.from(cleaned, 'base64').toString('binary');
    console.log("Decoded length:", decoded.length);
    
    const key1 = "9b09102b216d23cbb6cf75b47c82961c";
    const key2 = c;
    
    let result = "";
    for (let i = 0; i < decoded.length; i++) {
        const charCode = decoded.charCodeAt(i);
        const k1Char = key1.charCodeAt(i % 32);
        const k2Char = key2.charCodeAt(i % key2.length);
        const decryptedChar = charCode ^ k1Char ^ k2Char;
        result += String.fromCharCode(decryptedChar);
    }
    return result;
}

// Test data from Venom response:
const encryptedText = "b3RuMwNjfLrkSz1ZTCGE==hOoFYn";
const b = [[8,14],[6,9],[7,13],[8,13]];
const c = "8c2f1fe5f4dd89065e9ca13710fe8d71";
const postLink = "https://m.cimaleek.pw/movies/venom-633705/";
const IuAr = postLink.replace(/https?:\/\/[^\/]+\//, "").replace(/\/$/, "").length;
console.log("Computed IuAr:", IuAr); // Expected 20 for "movies/venom-633705"

const decrypted = decrypt(encryptedText, b, c, IuAr);
console.log("Decrypted URL:", decrypted);
