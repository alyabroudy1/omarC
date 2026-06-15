const https = require('https');
const fs = require('fs');

const url = 'https://cswru.vid872.top/e2/MkJCV1QzVWRCU0V5dlFWOWFlLzIyaHFlZGRFWXd4UUUwRzVxRHhaT0ZKcjlmemx0UnV5Zm5KazlWK1JGTkFYY2dZR2VSZ1FZWnJHdlQzUnlLMWFlM0s1OHp5ZGJxcHRWbVBoQlN0K2hwcWRZSHZKWEFTdmhkYmhYK2ZHeFo5NDRuR0ViSXJlK3g4dURHU3FwZngzWG1vM1ZtVE10ODVTZnpiZGFLS2twcGhoUWhYRC9uMDYrcDdQdUJaNVZjcmlDU2VMMTZBaTdpZi8zVEJDd0MzVHhrdnVFem56VG42Zk5iWmJEZVlDK3U4NDk1QWowQkYvZllMSDJUdFVwdWFnWDl3RGQzYlQ2TGpHdjRId1FORm9QSVVEWEtpVHR1TGY5blVIWS9iWlRic3VVL1ZKRXJzTi9qZlVJWUFMS3ExVHk.html';

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://m.cimaleek.pw/'
    }
};

https.get(url, options, (res) => {
    console.log(`Status: ${res.statusCode}`);
    console.log(`Headers:`, res.headers);
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        fs.writeFileSync('temp_decrypted_page.html', data);
        console.log(`Saved to temp_decrypted_page.html, size: ${data.length}`);
    });
}).on('error', (err) => {
    console.error(err);
});
