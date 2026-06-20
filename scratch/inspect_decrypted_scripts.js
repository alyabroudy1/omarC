const fs = require('fs');
const html = fs.readFileSync('temp_guy_decrypted_v2.html', 'utf8');

const regex = /<script[\s\S]*?>([\s\S]*?)<\/script>/gi;
let match;
let count = 0;
while ((match = regex.exec(html)) !== null) {
    const scriptContent = match[1].trim();
    if (scriptContent) {
        console.log(`Script ${count} (length ${scriptContent.length}):`);
        console.log(scriptContent.substring(0, 300) + "...\n");
        fs.writeFileSync(`scratch_script_${count}.js`, scriptContent);
        count++;
    }
}
