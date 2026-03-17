#!/usr/bin/env node

const https = require('https');
const http = require('http');
const { URL } = require('url');
const vm = require('vm');

async function fetch(url, headers = {}) {
    return new Promise((resolve, reject) => {
        const parsedUrl = new URL(url);
        const client = parsedUrl.protocol === 'https:' ? https : http;
        
        const options = {
            hostname: parsedUrl.hostname,
            path: parsedUrl.pathname + parsedUrl.search,
            method: 'GET',
            headers: {
                'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                ...headers
            }
        };

        const req = client.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(data));
        });
        req.on('error', reject);
        req.setTimeout(30000, () => { req.destroy(); reject(new Error('Request timeout')); });
        req.end();
    });
}

function extractWithBetterVM(html) {
    const scripts = html.match(/<script[^>]*>[\s\S]*?<\/script>/g) || [];
    let jsCode = '';
    
    for (const script of scripts) {
        if (script.includes('hlsPlaylist') || script.includes('mainPlayer.setup')) {
            jsCode = script.replace(/<script[^>]*>/, '').replace(/<\/script>/, '');
            break;
        }
    }
    
    if (!jsCode) return null;
    
    // Create a comprehensive proxy that handles property access more robustly
    const createProxy = (target = {}) => {
        return new Proxy(target, {
            get: (target, prop) => {
                // Handle special cases
                if (prop === 'length') return 0;
                if (prop === 'write') {
                    return function(html) {
                        console.log('document.write called with:', html?.substring(0, 200));
                    };
                }
                if (prop === 'createElement') {
                    return function() {
                        return {
                            setAttribute: () => {},
                            appendChild: () => {}
                        };
                    };
                }
                if (prop === 'querySelectorAll') {
                    return function() {
                        return [];
                    };
                }
                if (prop === 'getItem') {
                    return () => null;
                }
                if (prop === 'setItem' || prop === 'removeItem' || prop === 'clear') {
                    return () => {};
                }
                
                // For any other property, return a function that can handle being called
                // and returns another proxy for chaining
                return (...args) => {
                    // If we're dealing with array-like access, return undefined
                    if (typeof prop === 'string' && /^\d+$/.test(prop)) {
                        return undefined;
                    }
                    // Return a new proxy for potential chaining
                    return createProxy();
                };
            },
            set: (target, prop, value) => {
                target[prop] = value;
                return true;
            }
        });
    };
    
    const mockJqueryInstance = createProxy();
    
    const mockJquery = function(selector) {
        if (typeof selector === 'function') {
            try {
                selector();
            } catch (e) {
                // Ignore errors in selector execution
            }
        }
        return mockJqueryInstance;
    };
    mockJquery.ajax = function() { return mockJqueryInstance; };
    
    const context = {
        console: { 
            log: (...args) => {/* console.log(...args); */}, 
            error: (...args) => {/* console.error(...args); */}, 
            warn: (...args) => {/* console.warn(...args); */} 
        },
        window: createProxy(),
        document: createProxy({
            write: function(html) {
                // console.log('document.write called with:', html?.substring(0, 200));
            }
        }),
        jQuery: mockJquery,
        $: mockJquery,
        jwplayer: function(name) {
            return createProxy({
                setup: function() { return this; },
                on: function() { return this; },
                getPosition: function() { return 0; },
                load: function() {},
                play: function() {},
                seek: function() {}
            });
        },
        mainPlayer: createProxy({
            setup: function() { return this; },
            on: function() { return this; },
            getPosition: function() { return 0; },
            load: function() {},
            play: function() {},
            seek: function() {}
        }),
        setTimeout: function(callback, delay) {
            if (typeof callback === 'function') {
                // Try to execute the callback immediately for simpler obfuscation
                try {
                    callback();
                } catch (e) {
                    // Ignore errors
                }
            }
            return 0;
        },
        clearTimeout: function() {},
        hlsPlaylist: undefined,
        _0x4f1abc: undefined,
        _0x5e3ffb: undefined,
        localStorage: createProxy({
            getItem: () => null,
            setItem: () => {},
            removeItem: () => {},
            clear: () => {}
        })
    };
    
    try {
        vm.createContext(context);
        vm.runInContext(jsCode, context);
        
        // Check for hlsPlaylist in the context
        if (context.hlsPlaylist) {
            const p = context.hlsPlaylist;
            if (p && p.sources && Array.isArray(p.sources) && p.sources[0]) {
                return p.sources[0].file || p.sources[0].src;
            }
        }
        
        // Check for any _0x prefixed variables that might contain the playlist
        for (const key of Object.keys(context)) {
            if (key.startsWith('_0x') && context[key]) {
                const val = context[key];
                if (val && typeof val === 'object') {
                    // Check for sources array
                    if (val.sources && Array.isArray(val.sources) && val.sources[0]) {
                        return val.sources[0].file || val.sources[0].src;
                    }
                    // Check for files array
                    if (val.files && Array.isArray(val.files) && val.files[0]) {
                        return val.files[0].file || val.files[0].src;
                    }
                }
            }
        }
        
    } catch (e) {
        console.error('VM Error:', e.message);
        // Don't log the full JS code as it can be very large
        // console.log('JavaScript code that caused error:', jsCode.substring(0, 500));
    }
    
    return null;
}

async function main(inputUrl) {
    console.log('Fetching player page...');
    const html = await fetch(inputUrl);
    
    // Try to extract m3u8 URL via regex first (fallback)
    const m3u8Match = html.match(/https:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/);
    if (m3u8Match) {
        console.log('\n✅ EXTRACTED VIDEO URL (via regex):');
        console.log(m3u8Match[0]);
        return;
    }
    
    console.log('Trying Node VM JavaScript evaluation...');
    const videoUrl = extractWithBetterVM(html);
    
    if (videoUrl) {
        console.log('\n✅ EXTRACTED VIDEO URL:');
        console.log(videoUrl);
    } else {
        console.log('\n❌ Could not extract video URL');
        console.log('This specific obfuscation requires Mozilla Rhino JS engine (used in Kotlin).');
        console.log('The Kotlin FaselHDExtractor handles this properly.');
    }
}

const args = process.argv.slice(2);
if (args.length === 0) {
    console.log('Usage: node fasel_extract.js <faselhd_player_url>');
    console.log('\nNote: The current FaselHD format requires JavaScript evaluation');
    console.log('The Kotlin FaselHDExtractor with Rhino JS handles this properly.');
    process.exit(1);
}

main(args[0]).catch(console.error);