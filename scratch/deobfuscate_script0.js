const fs = require('fs');

// We copy the string array and decrypter from cimanow_script0.js
function pR_QzOmRS_ZXne(){
    const D$hihB=['f1e6e2e7fad0f7e2f7e6','edecf4','b5b6b2b0bbb4f1e7eaeccbc1','e1ece7fa','e0e2edf5e2f0','f3e2e7e7eaede4','e7f1e2f4caeee2e4e6','b1b3cbc2f7c7caf6','e0e6edf7e6f1','a0e5b7b7b0b0b5','b6b3f3fb','ede2f7f6f1e2efcbe6eae4ebf7','f7ebe6ed','f3e2f1e6edf7cdece7e6','f3ecf0eaf7eaecedb9e5eafbe6e7b8efe6e5f7b9aebababababaf3fbb8f7ecf3b9b3b8f4eae7f7ebb9b2f3fbb8ebe6eae4ebf7b9b2f3fbb8ecf3e2e0eaf7fab9b3b8f3eceaedf7e6f1aee6f5e6edf7f0b9edecede6b8','f1e6eeecf5e6','c1d6d7d7cccd','e0f0f0d7e6fbf7','e7ece0f6eee6edf7c6efe6eee6edf7','b2b4bab4b3b4b7c7e8f6f2c1c9','f1e6e5e6f1f1e6f1d3ecefeae0fa','b1b5b1bbb7d2e7eaf5e5e4','e0f1ecf0f0ccf1eae4eaed','e0f1e6e2f7e6c6efe6eee6edf7','eaedede6f1cbd7cecf','f1e6eeecf5e6c6f5e6edf7cfeaf0f7e6ede6f1','e2e7e7c6f5e6edf7cfeaf0f7e6ede6f1','e2edecedfaeeecf6f0','f7e2e4cde2eee6','e7e6e0ece7eaede4','f4ebeaf7e6','d0c6c0d7cacccd','b2b7b6b5b4c1f6d9f3c5e5','e0ecefecf1','edece7e6d7faf3e6','b0b2b1e7f2e0f9f4eb','e1e2e0e8e4f1ecf6ede7','babbb1b7bbb6ece1f1d2cac0','f7e6fbf7c0ecedf7e6edf7','f3ecf3','efece2e7','e0ebeaefe7cdece7e6f0','f0f1e0','ebe6eae4ebf7','ede2f7f6f1e2efd4eae7f7eb','e4e6f7c0ecedf7e6fbf7','5a065a05a35a025b355a075a00a35a015a06a35b2b5b265a095a015b245a02a35a065a055b3aa35b245a075b265b3a5a075b245a055b245b29a35a075b295b295a065a005a05a35a065a05a35b245a075a065b375b245a045b2c5b2aa35a0b5b245a075b295b2e5a065a095a07a35a065b2f5b245a055b245a08','e4e6f7caeee2e4e6c7e2f7e2','e2f3f3e6ede7c0ebeaefe7','e7eaf5','b6b4b7b5b0b2b2e5f3e4c1efd6','e7eaf5','e6f5e6f1fa','b2b3f2f3ebc5c0fa','b4b5bab5b0bbebf1efcdd7d7','f7e6fbf7c2efeae4ed','ebf7f7f3f0b9acacf7f3e0ade4ecece4efe6f0faede7eae0e2f7eaecedade0eceeacf3eaeee4e2e7acb0b3bbb1b7babab6b5bbb6bbb6b1b0b0b3b0b7bcf7be','b0b3f3fb','e2f1eae2aeefe2e1e6ef','e4e6f7c2f7f7f1eae1f6f7e6','e5ecedf7d0eaf9e6','eee2fb','f0f7faefe6','e5efececf1','f3f6f0eb','e7e2f7e2','e2f0faede0','e0eceef3efe6f7e6','c2d1d7cac0cfc6','e5ecf1c6e2e0eb','f1e6eeecf5e6c0ebeaefe7','b1b5b7b3f4d3d7cbc9ed','b0b4b1d1f0d6f6c8cf','f4eae7f7eb','edecaef1e6e5e6f1f1e6f1','efe6ede4f7eb'];
    return D$hihB;
}

function rj$OkiqbwdpKXrZ(jKZZ_Wdu) {
    const CrZJrkqfjJqODOWUlz_k = pR_QzOmRS_ZXne();
    jKZZ_Wdu = jKZZ_Wdu - 388; // 0xed - 0x6e3 + 0xd5*9 = 237 - 1763 + 1215 = -311? Let's check the offset.
    // In code: jKZZ_Wdu=jKZZ_Wdu-(Math.trunc(0xed)+-parseInt(0x6e3)+0xd5*parseInt(0x9));
    // Math.trunc(0xed) = 237
    // -parseInt(0x6e3) = -1763
    // 0xd5 * 9 = 1215
    // 237 - 1763 + 1215 = -311.
    // So jKZZ_Wdu = jKZZ_Wdu - (-311) = jKZZ_Wdu + 311.
    // Let's calculate exactly:
    // 0xed = 237
    // 0x6e3 = 1763
    // 0xd5 * 0x9 = 1215
    // 237 + (-1763) + 1215 = -311.
    // So jKZZ_Wdu - (-311) = jKZZ_Wdu + 311.
    
    // Wait, let's verify with one of the strings:
    // In code: tTjwyLyFjC$ZGm_aHRccSt(0x1ac) -> rj$OkiqbwdpKXrZ(0x1ac)
    // If offset is -311, index = 0x1ac - (-311) = 428 + 311 = 739. But array length is ~80!
    // Ah! Let's check:
    // jKZZ_Wdu = jKZZ_Wdu - (237 - 1763 + 1215)
    // Wait! Math.trunc(0xed) is 237.
    // -parseInt(0x6e3) is -1763.
    // 0xd5 * parseInt(0x9) is 1215.
    // Sum = 237 - 1763 + 1215 = -311.
    // So jKZZ_Wdu = jKZZ_Wdu - (-311) = jKZZ_Wdu + 311.
    // Wait, if index is jKZZ_Wdu + 311, let's see. If jKZZ_Wdu is 0x1ac (428), index = 428 + 311 = 739.
    // But how can that be if the array has only 80 elements?
    // Let's re-read the array pR_QzOmRS_ZXne().
    // Wait, is there a translation or shift?
    // Ah, look at this:
    // (function(BUWate$cyHQstHQ,lRBTTUxmxDrA){ ... while(!![]){ try { ... nRY$QEMBBPv['push'](nRY$QEMBBPv['shift']()); } ... } })
    // Yes! The array is shifted (rotated) when the script starts!
    // The shift target is:
    // pR_QzOmRS_ZXne, parseInt(-0x4)*parseInt(0x3ebff)+0xce85*parseInt(0xb)+parseFloat(-0x56)*Math.trunc(-0x2f4d)
    // Let's calculate the target value:
    // -4 * 257023 = -1028092
    // 0xce85 * 11 = 52869 * 11 = 581559
    // -56 * -12109 = 678104
    // -1028092 + 581559 + 678104 = 231571.
    // So the loop runs until a math check matches this target value.
    // Let's just evaluate the shift in JS by running the array rotation logic directly!
    // Let's write a test script that executes that part of cimanow_script0.js in Node.js and prints out the decrypted values of all indexes.
}
