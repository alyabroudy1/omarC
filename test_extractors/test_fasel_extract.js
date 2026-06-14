/**
 * FaselHD Extractor Test Script
 * 
 * Tests extracting M3U8 URLs from the FaselHD video_player page.
 * 
 * Strategy: Fetch the raw HTML, then use JS evaluation to decode the
 * obfuscated strings and find M3U8 URLs.
 */

const https = require('https');
const http = require('http');
const { URL } = require('url');

const TARGET_URL = 'https://www.fasel-hd.cam/video_player?player_token=aWkxbng1eEVEUnFlSUlGRmxHWHp4eVJjSWtzbXI1Ni9yaGo3UTNQY3JtTkFNRUhGM0NkdTYxQkkvNWtPY2hMY2ZVUkRhNCs3RTNpVWlERVV0UnlMd0k1Y29zMm1XalpZcmN2MGJoSEVRM1Q2NVd4MWxaYmlXNzVqTEFpRXVlMEJCUFYzTnUrTjVRVHhWNDBnaWpzRmhFVldYQVRvdG9WY05odHdiMnhwOXlLVzRhN1lwMFBIQTVtK1R4K1hraElwbk93ZjVFVnJuMGw0YngwUWxhTjEyY1FsZlFiMU40bW9IZjVSS1hseldMcz06OmHo9TPzrMX48I1kdHjFfZs%3D';

function fetchUrl(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsed = new URL(url);
        const client = parsed.protocol === 'https:' ? https : http;
        
        const options = {
            hostname: parsed.hostname,
            port: parsed.port,
            path: parsed.pathname + parsed.search,
            method: 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/UQ1A.240105.004) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.43 Mobile Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5',
                'Referer': 'https://www.fasel-hd.cam/',
                ...headers
            }
        };
        
        const req = client.request(options, (res) => {
            // Follow redirects
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                console.log(`[REDIRECT] ${res.statusCode} -> ${res.headers.location}`);
                fetchUrl(res.headers.location, headers).then(resolve).catch(reject);
                return;
            }
            
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ statusCode: res.statusCode, headers: res.headers, body: data }));
        });
        
        req.on('error', reject);
        req.end();
    });
}

async function extractFromHtml(html) {
    console.log('\n=== EXTRACTION RESULTS ===\n');
    console.log(`HTML length: ${html.length} bytes`);
    
    // 1. Check for JWPlayer
    const jwpMatch = html.match(/jwplayer\.key\s*=\s*"([^"]+)"/);
    if (jwpMatch) {
        console.log(`\n[JWPlayer Key] ${jwpMatch[1]}`);
    }
    
    // 2. Try direct M3U8 regex on raw HTML
    const m3u8Regex = /https?:\/\/[^\s"'`<>]+\.m3u8[^\s"'`<>]*/gi;
    const m3u8Matches = html.match(m3u8Regex);
    if (m3u8Matches && m3u8Matches.length > 0) {
        console.log(`\n[Direct M3U8 URLs found] ${m3u8Matches.length}`);
        m3u8Matches.forEach((url, i) => console.log(`  ${i+1}. ${url}`));
    } else {
        console.log('\n[Direct M3U8] None found in raw HTML');
    }
    
    // 3. Check for scdns.io CDN references
    const scdnsRegex = /scdns\.io/gi;
    const scdnsMatches = html.match(scdnsRegex);
    console.log(`\n[scdns.io references] ${scdnsMatches ? scdnsMatches.length : 0}`);
    
    // 4. Try to find the obfuscated document.write content
    // The script builds a long string and calls document.write()
    // Let's look for the string fragments that form URLs
    const masterRegex = /master\.m3u8/gi;
    const masterMatches = html.match(masterRegex);
    console.log(`[master.m3u8 references] ${masterMatches ? masterMatches.length : 0}`);
    
    // 5. Look for quality labels
    const qualityRegex = /\b(1080p|720p|480p|360p|240p|320p)\b/gi;
    const qualityMatches = html.match(qualityRegex);
    console.log(`[Quality labels] ${qualityMatches ? [...new Set(qualityMatches)].join(', ') : 'none'}`);
    
    // 6. Look for the _0x56a7 decoder function and try to decode strings
    // The obfuscated code uses _0x56a7(index, key) to decode base64 strings from _0x2209 array
    const arrayMatch = html.match(/var\s+_0x5009ee\s*=\s*\[([^\]]+)\]/);
    if (arrayMatch) {
        console.log(`\n[String Array] Found _0x5009ee with encoded strings`);
        // Try to extract and decode the array elements
        const elements = arrayMatch[1].match(/'([^']+)'/g);
        if (elements) {
            console.log(`  Total encoded strings: ${elements.length}`);
            
            // Try base64 decode each
            const decoded = elements.map(e => {
                const raw = e.replace(/'/g, '');
                try {
                    const decoded = Buffer.from(raw, 'base64').toString('utf-8');
                    // Filter for readable strings
                    if (/^[\x20-\x7E]+$/.test(decoded)) {
                        return decoded;
                    }
                } catch(e) {}
                return null;
            }).filter(Boolean);
            
            console.log(`  Decoded readable strings: ${decoded.length}`);
            
            // Look for URL-related decoded strings
            const urlStrings = decoded.filter(s => 
                s.includes('http') || s.includes('.m3u8') || s.includes('scdns') || 
                s.includes('master') || s.includes('playlist') || s.includes('file') ||
                s.includes('sources') || s.includes('setup') || s.includes('jwplayer') ||
                s.includes('hlsPlaylist') || s.includes('quality')
            );
            
            if (urlStrings.length > 0) {
                console.log(`\n  URL/Player related decoded strings:`);
                urlStrings.forEach(s => console.log(`    - "${s}"`));
            }
            
            // Print ALL decoded strings for analysis
            console.log(`\n  ALL decoded strings:`);
            decoded.forEach((s, i) => console.log(`    [${i}] "${s}"`));
        }
    }
    
    // 7. Try to find the document.write call pattern
    // The main obfuscated script concatenates string fragments with + operators
    // Look for patterns like 'string1' + 'string2' that form URLs
    const writePattern = /document\[.*?\]\((.*?)\)/g;
    let writeMatch;
    let writeCount = 0;
    while ((writeMatch = writePattern.exec(html)) !== null) {
        writeCount++;
        if (writeCount <= 5) {
            console.log(`\n[document.write call ${writeCount}] ${writeMatch[1].substring(0, 200)}...`);
        }
    }
    if (writeCount > 5) console.log(`  ... and ${writeCount - 5} more`);
    
    // 8. Look for the specific obfuscated string concatenation pattern
    // _0x5a8a86(0x283,...) + _0x5f1f60(-0xb9,...) etc. are the decoded URL parts
    // Let's find the document.write call and extract the raw string being written
    const docWriteRegex = /document\[_0x\w+\([^)]+\)\]\(([^)]{100,})\)/;
    const docWriteMatch = html.match(docWriteRegex);
    if (docWriteMatch) {
        console.log(`\n[Main document.write payload] Found, length: ${docWriteMatch[1].length}`);
        
        // Count how many string literal fragments are in this call
        const literalCount = (docWriteMatch[1].match(/'[^']*'/g) || []).length;
        console.log(`  String literal fragments: ${literalCount}`);
        
        // Extract any string literals that look like URL parts
        const literals = docWriteMatch[1].match(/'([^']+)'/g);
        if (literals) {
            const urlParts = literals.filter(l => {
                const v = l.replace(/'/g, '');
                return v.includes('http') || v.includes('scdns') || v.includes('.m3u8') || 
                       v.includes('master') || v.includes('/') || v.includes('.');
            });
            if (urlParts.length > 0) {
                console.log(`  URL-like literal fragments:`);
                urlParts.forEach(p => console.log(`    ${p}`));
            }
        }
    }
    
    // 9. Try to find inline string literals containing URL fragments
    // In the raw HTML, look for consecutive string fragments that when joined form a URL
    const inlineStrings = html.match(/['"]([^'"]{2,50})['"]/g) || [];
    const urlFragments = inlineStrings.filter(s => {
        const v = s.replace(/['"]/g, '');
        return v.includes('scdns') || v.includes('master.m3u8') || v.includes('playlist.m3u8') ||
               (v.startsWith('//') && v.includes('.')) || v.includes('.c.scdns');
    });
    
    if (urlFragments.length > 0) {
        console.log(`\n[Inline URL fragments] ${urlFragments.length}`);
        urlFragments.forEach(f => console.log(`  ${f}`));
    }
    
    // 10. Look for the _0x5f1f60 / _0x5a8a86 function results embedded as string concatenation
    // that writes the quality button HTML. 
    // The pattern: 'https:' + '//master.c' + '.scdns.io/' etc.
    const concatUrlPattern = /['"]https?:['"].*?\.m3u8/gs;
    const concatMatches = html.match(concatUrlPattern);
    if (concatMatches) {
        console.log(`\n[Concatenated URL patterns] ${concatMatches.length}`);
        concatMatches.forEach((m, i) => console.log(`  ${i+1}. ${m.substring(0, 200)}`));
    }
    
    // 11. NEW: The strings in the obfuscated code are actually readable in the markdown output!
    // Let's look for the specific pattern where string fragments are joined:
    // '//master.c'+'.scdns.io/' etc.
    const fragmentJoinRegex = /['"]\/\/[^'"]*master[^'"]*['"]\s*\+\s*['"][^'"]*scdns[^'"]*['"]/g;
    const fragmentJoins = html.match(fragmentJoinRegex);
    if (fragmentJoins) {
        console.log(`\n[Fragment join patterns] ${fragmentJoins.length}`);
        fragmentJoins.forEach(f => console.log(`  ${f}`));
    }
}

async function main() {
    console.log('=== FaselHD Video Player Extraction Test ===\n');
    console.log(`Target: ${TARGET_URL.substring(0, 80)}...`);
    
    try {
        console.log('\nFetching page...');
        const response = await fetchUrl(TARGET_URL);
        console.log(`Status: ${response.statusCode}`);
        console.log(`Content-Type: ${response.headers['content-type']}`);
        console.log(`Body length: ${response.body.length}`);
        
        if (response.statusCode === 200) {
            await extractFromHtml(response.body);
        } else if (response.statusCode === 403) {
            console.log('\n[!] Got 403 - Cloudflare protection active');
            console.log('This confirms we need WebView/CfBypass for initial page load');
            
            // Even with 403, check if there's any useful content
            if (response.body.length > 0) {
                console.log(`\nResponse body preview: ${response.body.substring(0, 500)}`);
            }
        } else {
            console.log(`\nUnexpected status. Body preview: ${response.body.substring(0, 500)}`);
        }
    } catch (error) {
        console.error('Error:', error.message);
    }
}

main();
