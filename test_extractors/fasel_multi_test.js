// Generalized FaselHD deobfuscation script
// Works by extracting the string table + lookup functions and resolving all _0x calls
const fs = require('fs');
const { execSync } = require('child_process');

const ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
const referer = "https://www.faselhd.center/";

function fetchPage(url) {
    const cmd = `curl -s -L -A "${ua}" -e "${referer}" "${url}"`;
    return execSync(cmd, { maxBuffer: 1024 * 1024 * 5 }).toString();
}

function extractStreams(html) {
    const lines = html.split('\n');
    
    // Find lines with _0x obfuscation containing stream-related terms
    const obfuscatedLines = lines.filter(l => l.includes('_0x') && (l.includes('m3u8') || l.includes('scdns') || l.includes('hls') || l.includes("document[")));
    
    if (obfuscatedLines.length === 0) {
        console.log("No obfuscated lines found");
        return null;
    }
    
    // Process each obfuscated line
    for (const line of obfuscatedLines) {
        let js = line.replace(/<\/?script[^>]*>/gi, '').trim();
        const lastSemiHtml = js.lastIndexOf(';<');
        if (lastSemiHtml > 0) js = js.substring(0, lastSemiHtml + 1);
        
        // Find string array function: function _0xNAME(){var _0xXXX=[...];...return _0xNAME();}
        const arrayFuncMatch = js.match(/function (_0x[a-f0-9]+)\(\)\{var _0x[a-f0-9]+=\[/);
        if (!arrayFuncMatch) continue;
        const arrayFuncName = arrayFuncMatch[1];
        
        // Extract array function body
        const arrayIdx = js.indexOf(`function ${arrayFuncName}()`);
        const arrayEndStr = `return ${arrayFuncName}();}`;
        const arrayEnd = js.indexOf(arrayEndStr, arrayIdx);
        if (arrayEnd < 0) continue;
        const arrayFunc = js.substring(arrayIdx, arrayEnd + arrayEndStr.length);
        
        // Extract shuffler IIFE (from start to first ));var )
        const shufflerEnd = js.indexOf('));var ');
        if (shufflerEnd < 0) continue;
        const shufflerCode = js.substring(0, shufflerEnd + 2);
        
        // Find core lookup function: function _0xNAME(a,b){var x=ARRAYFUNC();...}
        const lookupRegex = new RegExp(`function (_0x[a-f0-9]+)\\(_0x[a-f0-9]+,_0x[a-f0-9]+\\)\\{var _0x[a-f0-9]+=${arrayFuncName}`);
        const lookupNameMatch = js.match(lookupRegex);
        if (!lookupNameMatch) continue;
        const lookupFuncName = lookupNameMatch[1];
        
        const lookupIdx = js.indexOf(`function ${lookupFuncName}(`);
        // The lookup function ends with: lookupFuncName(arg1,arg2);}
        const lookupEndPattern = `${lookupFuncName}(_0x`;
        let endIdx = js.indexOf(lookupEndPattern, lookupIdx + 30);
        endIdx = js.indexOf(';}', endIdx) + 2;
        const lookupFunc = js.substring(lookupIdx, endIdx);
        
        // Find ALL wrapper functions that reference the lookup
        // Pattern: function _0xNAME(...){return LOOKUP(...);}
        const wrapperRegex = new RegExp(`function (_0x[a-f0-9]+)\\([^)]+\\)\\{return ${lookupFuncName}\\([^}]+\\}`, 'g');
        const wrappers = [];
        let m;
        while ((m = wrapperRegex.exec(js)) !== null) {
            wrappers.push(m[0]);
        }
        
        console.log(`Found: array=${arrayFuncName}, lookup=${lookupFuncName}, ${wrappers.length} wrappers`);
        
        // Now build and eval just the string resolution machinery
        const evalCode = `
${arrayFunc}
${shufflerCode}
${lookupFunc}
${wrappers.join('\n')}
`;
        
        try {
            // Evaluate the string machinery
            eval(evalCode);
            
            // Now find all _0xWRAPPER(num,num,num,num) calls and resolve them
            const wrapperNames = wrappers.map(w => w.match(/function (_0x[a-f0-9]+)/)[1]);
            const callPattern = new RegExp(`(${wrapperNames.join('|')})\\((-?0x[a-f0-9]+(?:,-?0x[a-f0-9]+)*)\\)`, 'g');
            
            let resolved = js;
            let lastResolved = '';
            
            // Find the interesting part containing m3u8
            // Look for scdns or m3u8 context
            const m3u8Idx = js.indexOf('m3u8');
            if (m3u8Idx < 0) continue;
            
            // Find a good chunk around m3u8 (go back to find the URL start)
            let chunkStart = Math.max(0, m3u8Idx - 2000);
            let chunkEnd = Math.min(js.length, m3u8Idx + 2000);
            let chunk = js.substring(chunkStart, chunkEnd);
            
            // Resolve all wrapper function calls in this chunk
            let resolvedChunk = chunk.replace(callPattern, (match, funcName, args) => {
                try {
                    return "'" + eval(`${funcName}(${args})`) + "'";
                } catch (e) {
                    return match;
                }
            });
            
            console.log("\n=== Resolved chunk (around m3u8) ===");
            console.log(resolvedChunk.substring(0, 3000));
            
            // Extract URLs from resolved chunk
            const urls = resolvedChunk.match(/https?:\/\/[^\s"'<>]+\.m3u8/g);
            if (urls) {
                console.log("\n=== M3U8 URLs ===");
                urls.forEach((u, i) => console.log(`${i+1}. ${u}`));
                return urls;
            }
            
            // If no full URLs yet, try to find concatenation patterns and resolve them
            // Look for patterns like 'part1'+RESOLVED+'part2'+...
            const concatParts = resolvedChunk.match(/'[^']*'/g);
            if (concatParts) {
                const joined = concatParts.map(s => s.slice(1, -1)).join('');
                const urlsFromJoin = joined.match(/https?:\/\/[^\s"<>]+\.m3u8/g);
                if (urlsFromJoin) {
                    console.log("\n=== M3U8 URLs (from concatenation) ===");
                    urlsFromJoin.forEach((u, i) => console.log(`${i+1}. ${u}`));
                    return urlsFromJoin;
                }
            }
        } catch (e) {
            console.error("Eval error:", e.message);
        }
    }
    return null;
}

// Test with the provided tokens
const urls = [
    "https://web3126x.faselhdx.xyz/video_player?player_token=eUVUby9aVlIzTUxpeFRjMzNQNEYwaHZCNlp6dkpJM3lPTWQ5aE9QWElvZEs2SkxrUVNJZnY5S1dOQlZqN2lTQnAzV1lUZ2wvdEEyVjhGcHJ2M0ducHpUTzc3ZkVIMThTTndvNy9wMGlKaFhYcm9LdnJMSTNZWHNhdmxDaENGMEw1SFdBc2d5WGE5dWNTNjJEaXU0N29LU2g0ZGwwa0VaYXQ1dFd0bzBmOTd5dk0wai9waGtSemFvNFFqdkJwdS8yMHVqaytZMVFLK1NEVzEvd0JLWGpBcmZyTFZJSFUvSEpHMVFzYWZuZHpPMD06OkWLOuIJ6MI05H98r7aBXVM%3D",
    "https://web3126x.faselhdx.xyz/video_player?player_token=N1hYN1V4YnI1SlNocHE2UEhTdjd4Z1pkd29ULzh1bEFsUUhrb1hWSGF5YVp0aG1WYi80M21YNHM2YkhnUmtGb0FXMjl0bUxxWkxabWpsMU1KeHZqMklvbS9DVXZRREx4SU55c1hXc0lMS0ZJcjdVcGhMZTlzWGRtbGlkUzR3d05lQzdrRFhNNjZVNEZFQitDbThPTVVLQ3E3aStjVnRwMG5KcWpNdHd0emF6c0s3TTljc1Zaay96U0Q0dDNsdFYzT3ErQlEzVEEwYStIMFhkQ2UxNWJMUjhFVXJUQjRLTE9zYVY5RkxNajMxdz06OklTsUe%2FV4OBIi51lIn5vW0%3D"
];

for (let i = 0; i < urls.length; i++) {
    console.log(`\n${'='.repeat(60)}`);
    console.log(`Token ${i + 1}`);
    console.log('='.repeat(60));
    
    try {
        const html = fetchPage(urls[i]);
        console.log(`Fetched ${html.length} bytes`);
        extractStreams(html);
    } catch (e) {
        console.error(`Fetch error:`, e.message);
    }
}
