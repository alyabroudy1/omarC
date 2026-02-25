const puppeteer = require('puppeteer');
const fs = require('fs');

(async () => {
    console.log("Launching Puppeteer...");
    // Use headless: "new" and add stealth-like args
    const browser = await puppeteer.launch({
        headless: "new",
        args: [
            '--no-sandbox',
            '--disable-setuid-sandbox',
            '--disable-blink-features=AutomationControlled'
        ]
    });
    const page = await browser.newPage();

    // Set a realistic User-Agent
    await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');

    // Bypass webdriver detection
    await page.evaluateOnNewDocument(() => {
        Object.defineProperty(navigator, 'webdriver', { get: () => false });
    });

    const targetUrl = 'https://savefiles.com/e/fepgxkbktt8t';
    console.log(`Navigating to ${targetUrl}...`);

    await page.goto(targetUrl, { waitUntil: 'networkidle2' });

    // Wait a bit for CF or JS to run
    console.log("Waiting 3 seconds just in case...");
    await new Promise(r => setTimeout(r, 3000));

    console.log("Checking for interstitial form...");
    const hasForm = await page.$('form[action="/dl"]');
    if (hasForm) {
        console.log("Submitting interstitial form to get to the player...");
        await Promise.all([
            page.waitForNavigation({ waitUntil: 'networkidle0' }),
            page.click('form[action="/dl"] button[type="submit"]')
        ]).catch(e => console.log("Navigation timeout, continuing anyway..."));
    } else {
        console.log("No interstitial form found.");
    }

    console.log("Fetching page source...");
    const html = await page.content();
    fs.writeFileSync('puppeteer_dump.html', html);
    console.log(`Saved HTML (Length: ${html.length}) to puppeteer_dump.html`);

    // Let's also take a screenshot to see what the browser sees
    await page.screenshot({ path: 'puppeteer_screenshot.png' });
    console.log("Saved screenshot to puppeteer_screenshot.png");

    // Test logic
    let match = html.match(/sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']/);
    let videoUrl = match ? match[1] : null;

    if (!videoUrl) {
        console.log("Direct regex failed.");
        let p1 = html.split('eval(function(p,a,c,k,e,d)');
        if (p1.length > 1) {
            console.log("Packed JS found!");
        } else {
            console.log("No Packed JS found either.");

            // Check for iframes
            const iframes = await page.$$eval('iframe', iframes => iframes.map(i => i.src));
            console.log("Iframes found on page:", iframes);
        }
    } else {
        console.log(`Raw Extracted videoUrl: ${videoUrl}`);
    }

    if (videoUrl && !videoUrl.startsWith('http') && !videoUrl.startsWith('//')) {
        console.log('URL is not HTTP. Attempting Base64 decode...');
        try {
            let decoded = Buffer.from(videoUrl, 'base64').toString('utf8');
            if (decoded.startsWith('http')) {
                videoUrl = decoded;
                console.log('SUCCESS: Decoded Base64 video URL -> ' + videoUrl);
            }
        } catch (e) {
            console.log('Base64 decode failed');
        }
    }

    await browser.close();
})();
