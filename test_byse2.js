const https = require('https');
const crypto = require('crypto');

const videoId = "b4k8fd694vhj";
const apiUrl = `https://bysezejataos.com/api/videos/${videoId}/playback`;

console.log("Fetching API:", apiUrl);

https.get(apiUrl, (res) => {
    let data = "";
    res.on("data", chunk => data += chunk);
    res.on("end", () => {
        const json = JSON.parse(data);
        const playback = json.playback;
        
        console.log("\n=== DECRYPTING ===");
        console.log("IV:", playback.iv);
        console.log("key_parts:", playback.key_parts);
        
        // Decode key_parts to get the main key
        const keyPart0 = Buffer.from(playback.key_parts[0], 'base64');
        const keyPart1 = Buffer.from(playback.key_parts[1], 'base64');
        const mainKey = Buffer.concat([keyPart0, keyPart1]);
        console.log("Main key (32 bytes):", mainKey.toString('hex'));
        
        // Decode IV from base64
        const iv = Buffer.from(playback.iv, 'base64');
        console.log("IV (12 bytes):", iv.toString('hex'));
        
        // Decode payload from base64
        const payload = Buffer.from(playback.payload, 'base64');
        const ciphertext = payload.subarray(0, payload.length - 16);
        const authTag = payload.subarray(payload.length - 16);
        console.log("Ciphertext:", ciphertext.length, "bytes");
        console.log("AuthTag:", authTag.toString('hex'));
        
        // Try decrypt with main key only (no decrypt_keys!)
        console.log("\n=== TRYING MAIN KEY ONLY ===");
        try {
            const decipher = crypto.createDecipheriv('aes-256-gcm', mainKey, iv);
            decipher.setAuthTag(authTag);
            
            const decrypted = Buffer.concat([
                decipher.update(ciphertext),
                decipher.final()
            ]);
            console.log("SUCCESS! Decrypted:", decrypted.toString('utf8'));
        } catch(e) {
            console.log("Failed with main key:", e.message);
        }
        
        // The decrypt_keys might be for edge cases - try using them as the key directly
        console.log("\n=== TRYING DECRYPT KEYS AS MAIN KEY ===");
        for (const [keyName, keyValue] of Object.entries(playback.decrypt_keys)) {
            try {
                const decryptKey = Buffer.from(keyValue, 'base64');
                console.log(`\nKey '${keyName}' (${decryptKey.length} bytes):`, decryptKey.toString('hex'));
                
                const decipher = crypto.createDecipheriv('aes-256-gcm', decryptKey, iv);
                decipher.setAuthTag(authTag);
                
                const decrypted = Buffer.concat([
                    decipher.update(ciphertext),
                    decipher.final()
                ]);
                console.log(`SUCCESS with ${keyName}!`);
                console.log("Decrypted:", decrypted.toString('utf8').substring(0, 200));
                break;
            } catch(e) {
                console.log(`Key '${keyName}' failed:`, e.message);
            }
        }
        
        // Maybe the decrypt_keys are XOR'd with something or used differently?
        // Let me try XOR combining the decrypt key with the main key
        console.log("\n=== TRYING XOR COMBINATION ===");
        for (const [keyName, keyValue] of Object.entries(playback.decrypt_keys)) {
            try {
                const decryptKey = Buffer.from(keyValue, 'base64');
                if (decryptKey.length !== 16) continue;
                
                // XOR main key with decrypt key
                const xoredKey = Buffer.alloc(32);
                for (let i = 0; i < 32; i++) {
                    xoredKey[i] = mainKey[i] ^ decryptKey[i % 16];
                }
                
                const decipher = crypto.createDecipheriv('aes-256-gcm', xoredKey, iv);
                decipher.setAuthTag(authTag);
                
                const decrypted = Buffer.concat([
                    decipher.update(ciphertext),
                    decipher.final()
                ]);
                console.log(`XOR SUCCESS with ${keyName}!`);
                console.log("Decrypted:", decrypted.toString('utf8').substring(0, 200));
                break;
            } catch(e) {
                console.log(`XOR with ${keyName} failed:`, e.message);
            }
        }
        
        // Check if there's iv2/payload2 - maybe we need those
        if (playback.iv2 && playback.payload2) {
            console.log("\n=== TRYING IV2/PAYLOAD2 ===");
            console.log("iv2:", playback.iv2);
            console.log("payload2 length:", playback.payload2.length);
            
            const iv2 = Buffer.from(playback.iv2, 'base64');
            const payload2 = Buffer.from(playback.payload2, 'base64');
            const ciphertext2 = payload2.subarray(0, payload2.length - 16);
            const authTag2 = payload2.subarray(payload2.length - 16);
            
            console.log("iv2 size:", iv2.length);
            
            try {
                const decipher = crypto.createDecipheriv('aes-256-gcm', mainKey, iv2);
                decipher.setAuthTag(authTag2);
                
                const decrypted = Buffer.concat([
                    decipher.update(ciphertext2),
                    decipher.final()
                ]);
                console.log("SUCCESS with iv2/payload2!");
                console.log("Decrypted:", decrypted.toString('utf8'));
            } catch(e) {
                console.log("iv2/payload2 failed:", e.message);
            }
        }
        
    });
}).on('error', e => console.error("Request error:", e.message));