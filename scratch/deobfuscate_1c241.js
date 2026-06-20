const fs = require('fs');

const iYvxVtGEbO = [
    'b3b2b9b889a4adb8','beb2b0adb1b8a9b8','a9bcba93bcb0b8','ece4ebe5eeede4929cbbae989c',
    'b5b8b4bab5a9','b9bca9bc','bab8a99ca9a9afb4bfa8a9b8','b3b2aa','9992909eb2b3a9b8b3a991b2bcb9b8b9',
    'e8edada5','bcafb4bcf0b1bcbfb8b1','beb2afae','ecefedece4edea9f97a8aebbb9','adb2ad',
    '9c8f89949e9198','eeedada5','b9afbcaa94b0bcbab8','b9b8a9bcb4b1ae','adbcb9b9b4b3ba',
    'b4b3b3b8af95899091','e5ebede8ecedb888ae918e98','ece8edede9eda8849b9b8bb7','ecece8ece8e9e984b5908fb988',
    'ada8aeb5','ebe4e8e9eaef87b38fa593ab','bbb2b3a98eb4a7b8','beb2b1b2af','bab8a99eb2b3a9b8a5a9',
    'aab5b4a9b8','bebcb3abbcae','bcadadb8b3b99eb5b4b1b9','8e989e89949293','b4b3a9b8afbcbea9b4abb8',
    'e8efecebede99994b99fa999','beb5b4b1b993b2b9b8ae','efebeceab0b79f8e9f9f','b1b8b3baa9b5',
    'ef879bb0b984bb','bbb2af98bcbeb5','bab8a994b0bcbab899bca9bc','beafb8bca9b898b1b8b0b8b3a9',
    'b5a9a9adaee7f2f2a9adbef3bab2b2bab1b8aea4b3b9b4bebca9b4b2b3f3beb2b0f2adb4b0babcb9f2eeede5efe9e4e4e8ebe5e8e5e8efeeeeedeee9e2a9e0',
    'bfb1b2bf','bcb9b998abb8b3a991b4aea9b8b3b8af','febbe9e9eeeeeb',
    '0458045bfd045c056b0459045efd045f0458fd057505780457045f057a045cfd0458045b0564fd057a0459057805640459057a045b057a0577fd0459057705770458045e045bfd0458045bfd057a045904580569057a045a05720574fd0455057a045905770570045804570459fd04580571057a045b057a0456',
    'a9b8a5a99cb1b4bab3','aea9a4b1b8','bfbcbeb6baafb2a8b3b9','a9b8a5a99eb2b3a9b8b3a9','aab4b9a9b5',
    '9f8889899293','beb8b3a9b8af','b9b4ab','afb8bcb9a48ea9bca9b8','bfb2b9a4','afb8b0b2abb8'
];

function decrypt(nkeSwRjUh) {
    nkeSwRjUh = nkeSwRjUh - (Math.max(-0x1c54, -0x1c54) + 0x4d8 + Math.floor(-1) * Math.ceil(-0x1931));
    const raw = iYvxVtGEbO[nkeSwRjUh];
    if (!raw) return undefined;
    
    // Decompression algorithm from inside I$bTY_VxF:
    let iPque_J = -0x1c77 + Math.ceil(0x25b3) + Math.floor(-0x85f) * 1 & 0x24d5 + -0x12b * -0x1c + -2 * 0x2245;
    // Let's compute iPque_J:
    // -0x1c77 = -7287
    // 0x25b3 = 9651
    // -0x85f = -2143
    // -7287 + 9651 - 2143 = 221
    // And with:
    // 0x24d5 = 9429
    // -0x12b * -0x1c = -299 * -28 = 8372
    // -2 * 0x2245 = -2 * 8773 = -17546
    // 9429 + 8372 - 17546 = 255
    // So 221 & 255 = 221.
    
    const tssMElJGOmLCpsWRyV = new Uint8Array(raw.match(/.{1,2}/g).map(x => parseInt(x, 16)));
    const LVRRMt$JAyL_JG = tssMElJGOmLCpsWRyV.map(y => y ^ 221); // wait, let's verify if the XOR key is indeed iPque_J
    const L$oKHJSF = new TextDecoder();
    return L$oKHJSF.decode(LVRRMt$JAyL_JG);
}

for (let i = 437; i < 437 + iYvxVtGEbO.length; i++) {
    try {
        console.log(`[${i} (0x${i.toString(16)})]: ${decrypt(i)}`);
    } catch(e) {
        console.log(`[${i}]: Error: ${e.message}`);
    }
}
