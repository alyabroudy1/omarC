const fs = require('fs');

const html = fs.readFileSync('C:/Users/abu-o/.gemini/antigravity-ide/brain/2345fa2d-1b7f-486c-915d-447b43965288/.system_generated/steps/94/content.md', 'utf-8');

const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]);

console.log(`Found ${scripts.length} scripts`);

const obfuscated = scripts.filter(s => /_0x[a-f0-9]{4,}/i.test(s));
console.log(`Found ${obfuscated.length} obfuscated scripts`);

obfuscated.forEach((s, i) => {
    console.log(`\nScript ${i}: length ${s.length}`);
});

// Let's try running them in a mocked environment
const vm = require('vm');

for (let i = 0; i < obfuscated.length; i++) {
    console.log(`\nEvaluating Script ${i}...`);
    try {
        const sandbox = {
            console: { log: console.log, error: console.error, warn: () => {}, info: () => {}, debug: () => {} },
            window: { navigator: { userAgent: '' }, location: { href: '', hostname: '' } },
            document: {
                write: (str) => console.log(`[document.write] ${str.substring(0, 100)}...`),
                createElement: () => ({}),
                getElementById: () => ({}),
                getElementsByClassName: () => [],
                querySelector: () => ({}),
                querySelectorAll: () => [],
            },
            jwplayer: () => ({
                setup: (cfg) => {
                    console.log(`[jwplayer.setup] ${JSON.stringify(cfg).substring(0, 200)}...`);
                    sandbox._capturedConfig = cfg;
                    return { on: () => {}, getPosition: () => 0, load: () => {}, play: () => {}, seek: () => {} };
                }
            }),
            mainPlayer: {
                setup: (cfg) => {
                    console.log(`[mainPlayer.setup] ${JSON.stringify(cfg).substring(0, 200)}...`);
                    sandbox._capturedConfig = cfg;
                    return { on: () => {}, getPosition: () => 0, load: () => {}, play: () => {}, seek: () => {} };
                },
                on: () => {}, getPosition: () => 0, load: () => {}, play: () => {}, seek: () => {}
            },
            setTimeout: (fn) => {},
            clearTimeout: () => {},
            hlsPlaylist: undefined,
            _capturedConfig: null,
            $: () => ({ on: () => {}, fadeIn: () => {}, fadeOut: () => {}, addClass: () => {}, removeClass: () => {}, attr: () => null, click: () => {} })
        };
        
        vm.createContext(sandbox);
        vm.runInContext(obfuscated[i], sandbox);
        
        if (sandbox._capturedConfig) {
            console.log(`[SUCCESS] Captured config: ${JSON.stringify(sandbox._capturedConfig).substring(0, 200)}`);
        } else if (sandbox.hlsPlaylist) {
            console.log(`[SUCCESS] Captured hlsPlaylist: ${JSON.stringify(sandbox.hlsPlaylist).substring(0, 200)}`);
        } else {
            console.log(`[FAIL] No config captured`);
        }
        
    } catch (e) {
        console.log(`[ERROR] ${e.message}`);
    }
}
