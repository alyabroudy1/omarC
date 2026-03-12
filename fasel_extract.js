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
    
    const mockJquery = function(selector) {
        if (typeof selector === 'function') {
            setTimeout(selector, 0);
            return;
        }
        return {
            on: () => {},
            fadeIn: () => {},
            fadeOut: () => {},
            addClass: () => {},
            removeClass: () => {},
            attr: () => {},
            click: () => {}
        };
    };
    mockJquery.ajax = () => {};
    
    const context = {
        console: { log: () => {}, error: () => {}, warn: () => {} },
        window: {},
        document: { 
            write: () => {}, 
            createElement: () => ({ setAttribute: () => {}, appendChild: () => {} }), 
            head: {}, 
            body: {},
            querySelectorAll: () => [],
            write: function(html) {
                console.log('document.write called with:', html?.substring(0, 200));
            }
        },
        jQuery: mockJquery,
        $: mockJquery,
        jwplayer: function(name) {
            return {
                setup: function() { return this; },
                on: function() { return this; },
                getPosition: function() { return 0; },
                load: function() {},
                play: function() {},
                seek: function() {}
            };
        },
        mainPlayer: {
            setup: function() { return this; },
            on: function() { return this; },
            getPosition: function() { return 0; },
            load: function() {},
            play: function() {},
            seek: function() {}
        },
        setTimeout: () => {},
        clearTimeout: () => {},
        hlsPlaylist: undefined,
        _0x4f1abc: undefined,
        _0x5e3ffb: undefined
    };
    
    try {
        vm.createContext(context);
        vm.runInContext(jsCode, context);
        
        console.log('After JS eval, checking for hlsPlaylist...');
        
        if (context.hlsPlaylist) {
            const p = context.hlsPlaylist;
            console.log('Found hlsPlaylist:', typeof p);
            if (p.sources && p.sources[0]) {
                return p.sources[0].file || p.sources[0].src;
            }
        }
        
        for (const key of Object.keys(context)) {
            if (key.startsWith('_0x') && context[key]) {
                const val = context[key];
                if (typeof val === 'object') {
                    if (val.sources && val.sources[0]) {
                        return val.sources[0].file || val.sources[0].src;
                    }
                    if (val.files && val.files[0]) {
                        return val.files[0].file || val.files[0].src;
                    }
                }
            }
        }
        
    } catch (e) {
        console.error('VM Error:', e.message);
    }
    
    return null;
}

async function main(inputUrl) {
    console.log('Fetching player page...');
    const html = await fetch(inputUrl);
    
    console.log('Trying Node VM JavaScript evaluation...');
    const videoUrl = extractWithBetterVM(html);
    
    if (videoUrl) {
        console.log('\n✅ EXTRACTED VIDEO URL:');
        console.log(videoUrl);
    } else {
        console.log('\n❌ Could not extract video URL');
        console.log('This specific obfuscation requires the Kotlin RhinoJsStrategy to evaluate properly.');
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
