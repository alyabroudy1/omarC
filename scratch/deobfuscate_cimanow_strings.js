const fs = require('fs');

const D$hihB = ['f1e6e2e7fad0f7e2f7e6', 'edecf4', 'b5b6b2b0bbb4f1e7eaeccbc1', 'e1ece7fa', 'e0e2edf5e2f0', 'f3e2e7e7eaede4', 'e7f1e2f4caeee2e4e6', 'b1b3cbc2f7c7caf6', 'e0e6edf7e6f1', 'a0e5b7b7b0b0b5', 'b6b3f3fb', 'ede2f7f6f1e2efcbe6eae4ebf7', 'f7ebe6ed', 'f3e2f1e6edf7cdece7e6', 'f3ecf0eaf7eaecedb9e5eafbe6e7b8efe6e5f7b9aebababababaf3fbb8f7ecf3b9b3b8f4eae7f7ebb9b2f3fbb8ebe6eae4ebf7b9b2f3fbb8ecf3e2e0eaf7fab9b3b8f3eceaedf7e6f1aee6f5e6edf7f0b9edecede6b8', 'f1e6eeecf5e6', 'c1d6d7d7cccd', 'e0f0f0d7e6fbf7', 'e7ece0f6eee6edf7c6efe6eee6edf7', 'b2b4bab4b3b4b7c7e8f6f2c1c9', 'f1e6e5e6f1f1e6f1d3ecefeae0fa', 'b1b5b1bbb7d2e7eaf5e5e4', 'e0f1ecf0f0ccf1eae4eaed', 'e0f1e6e2f7e6c6efe6eee6edf7', 'eaedede6f1cbd7cecf', 'f1e6eeecf5e6c6f5e6edf7cfeaf0f7e6ede6f1', 'e2e7e7c6f5e6edf7cfeaf0f7e6ede6f1', 'e2edecedfaeeecf6f0', 'f7e2e4cde2eee6', 'e7e6e0ece7eaede4', 'f4ebeaf7e6', 'd0c6c0d7cacccd', 'b2b7b6b5b4c1f6d9f3c5e5', 'e0ecefecf1', 'edece7e6d7faf3e6', 'b0b2b1e7f2e0f9f4eb', 'e1e2e0e8e4f1ecf6ede7', 'babbb1b7bbb6ece1f1d2cac0', 'f7e6fbf7c0ecedf7e6edf7', 'f3ecf3', 'efece2e7', 'e0ebeaefe7cdece7e6f0', 'f0f1e0', 'ebe6eae4ebf7', 'ede2f7f6f1e2efd4eae7f7eb', 'e4e6f7c0ecedf7e6fbf7', '5a065a05a35a025b355a075a00a35a015a06a35b2b5b265a095a015b245a02a35a065a055b3aa35b245a075b265b3a5a075b245a055b245b29a35a075b295b295a065a005a05a35a065a05a35b245a075a065b375b245a045b2c5b2aa35a0b5b245a075b295b2e5a065a095a07a35a065b2f5b245a055b245a08', 'e4e6f7caeee2e4e6c7e2f7e2', 'e2f3f3e6ede7c0ebeaefe7', 'e7eaf5', 'b6b4b7b5b0b2b2e5f3e4c1efd6', 'e7e6f7e2eaeff0', 'e6f5e6f1fa', 'b2b3f2f3ebc5c0fa', 'b4b5bab5b0bbebf1efcdd7d7', 'f7e6fbf7c2efeae4ed', 'ebf7f7f3f0b9acacf7f3e0ade4ecece4efe6f0faede7eae0e2f7eaecedade0eceeacf3eaeee4e2e7acb0b3bbb1b7babab6b5bbb6bbb6b1b0b0b3b0b7bcf7be', 'b0b2f3fb', 'e2f1eae2aeefe2e1e6ef', 'e4e6f7c2f7f7f1eae1f6f7e6', 'e5ecedf7d0eaf9e6', 'eee2fb', 'f0f7faefe6', 'e5efececf1', 'f3f6f0eb', 'e7e2f7e2', 'e2f0faede0', 'e0eceef3efe6f7e6', 'c2d1d7cac0cfc6', 'e5ecf1c6e2e0eb', 'f1e6eeecf5e6c0ebeaefe7', 'b1b5b7b3f4d3d7cbc9ed', 'b0b4b1d1f0d6f6c8cf', 'f4eae7f7eb', 'edecaef1e6e5e6f1f1e6f1', 'efe6ede4f7eb'];

const WGJqTb = 131;

D$hihB.forEach((hex, i) => {
    try {
        const bytes = hex.match(/.{1,2}/g).map(byte => parseInt(byte, 16));
        const decodedBytes = bytes.map(b => b ^ WGJqTb);
        const decoded = Buffer.from(decodedBytes).toString('utf8');
        console.log(`String ${i}: "${decoded}"`);
    } catch(e) {
        console.log(`String ${i} failed to decode:`, e.message);
    }
});
