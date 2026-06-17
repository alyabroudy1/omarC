const fs = require('fs');

function reMatch(text, pattern) {
    const re = new RegExp(pattern);
    const match = re.exec(text);
    return match ? match[1] : null;
}

// Read the saved HTML
const html = fs.readFileSync('/tmp/freex2line_debug.html', 'utf-8');

console.log('=== Testing New Challenge Format Parsing ===\n');

// 1. Find ptr_XXXX
const ptrMatch = reMatch(html, "window\\.ptr_\\w+\\s*=\\s*'([^']+)'");
console.log('1. ptrMatch (ctx name):', ptrMatch);

// 2. Extract context object
const ctxName = ptrMatch;
const ctxJson = reMatch(html, `(?:window\\[)?['"]${ctxName}['"](?:\\])?\\s*=\\s*\\{([^}]+)\\}`);
console.log('2. ctxJson:', ctxJson ? ctxJson.substring(0, 200) : 'null');

// 3. Extract map
const mapMatch = reMatch(html, "window\\.map_\\w+\\s*=\\s*\\{([^}]+)\\}");
console.log('3. mapMatch:', mapMatch ? mapMatch.substring(0, 200) : 'null');

// 4. Extract keys from map
const chKey = reMatch(mapMatch, "ch:\\s*'([^']+)'");
const riKey = reMatch(mapMatch, "ri:\\s*'([^']+)'");
const keKey = reMatch(mapMatch, "ke:\\s*'([^']+)'");
const seKey = reMatch(mapMatch, "se:\\s*'([^']+)'");
console.log('4. Map keys: ch→', chKey, ', ri→', riKey, ', ke→', keKey, ', se→', seKey);

// 5. Look up values from context
if (chKey) console.log('   ch value:', reMatch(ctxJson, `'?${chKey}'?\\s*:\\s*'([^']+)'`));
if (riKey) console.log('   ri value:', reMatch(ctxJson, `'?${riKey}'?\\s*:\\s*'([^']+)'`));
if (keKey) console.log('   ke value:', reMatch(ctxJson, `'?${keKey}'?\\s*:\\s*'([^']+)'`));
if (seKey) console.log('   se value:', reMatch(ctxJson, `'?${seKey}'?\\s*:\\s*'([^']+)'`));

console.log('\n=== Testing _0x_cfg fallback ===\n');

const cfgText = reMatch(html, "(?:var|let|const|window\\.)?\\s*_0x_cfg\\s*=\\s*\\{([^}]+)\\}");
console.log('cfgText:', cfgText ? cfgText.substring(0, 200) : 'null');

const cVar = reMatch(cfgText, "'?c'?:\\s*'([^']+)'");
const rVar = reMatch(cfgText, "'?r'?:\\s*'([^']+)'");
const kVar = reMatch(cfgText, "'?k'?:\\s*'([^']+)'");
const sVar = reMatch(cfgText, "'?s'?:\\s*'([^']+)'");
console.log('cfg values: c=', cVar, ', r=', rVar, ', k=', kVar ? kVar.substring(0, 20) + '...' : 'null', ', s=', sVar);
