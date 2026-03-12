const fs = require('fs');

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const url1 = "https://web3126x.faselhdx.xyz/video_player?player_token=NHNrN3hLV1UwazJzaUFCK0d1Zkt1Q0dQUXRJTzBoYXZxV002VGlBSUdKRXB4QkMyRW5JUDdLSnJ3NmpCN1Rqd1VJUTdIMXdSV3JlaERIZ3J2Q3RqMEN0NSs1TkVlNEFrMkx6ZjF5Mjh6QitTaWFrRm5NY1hTdjMvUm5MQ2ZlSmhTSkd1cDlpNUxhSG15TEJHWXJyaCtGU1F1SlllMW9Eb05zWGpkcmJIUDQySk5yOTZiOGJvM0x6WmozMDNPMk9oWDV2emxNVVBvbFZvY3JHNjcyS2hEMWN5SnNjUDRid0ZRZnBzSEx4TThpbz06OnWpCuCLz1sqqJRlvzWsi2Y%3D";
const url2 = "https://web3126x.faselhdx.xyz/video_player?player_token=MDRJNFc1NEVGb0lZRXRpVXpFSjllYk9mdHJ3N1dqSXBTaGhqVDVpNjlldm1NdDJLNzlPVzM1dmowcm5lOEhabzRlU2crdmdEOHdoalRTN1RBYzRORDRKajNzb2l4OHNzWEhXYkRrczU4UktLWk1UeUlzSXAxRkdNK0pFVEwwTk43VEpDRng2Q2NiSEdiOW1HQVNRVHBPbnZWZkd2RDcwZjgwOVRrb0NLcmNibk9BcG5JYStiaXgxK1lNdE0zMGJ2WkRxRjNLK1c4VC9NWlRGZlRRS2FZT2lSL3RqNDJKU1NuY0dZd2thWVdHa2F3SlNuclZiKzd2VkJENnB5dWRZUDo6M1eE%2BjKbArX7Q4PIyn1XiA%3D%3D";

async function fetchHtml(url) {
    const response = await fetch(url, {
        headers: {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Referer": "https://web31112x.faselhdx.xyz/"
        }
    });
    return response.text();
}

async function main() {
    console.log("Fetching URL 1...");
    const html1 = await fetchHtml(url1);
    fs.writeFileSync('server1.html', html1);
    console.log("Saved to server1.html, size:", html1.length);

    console.log("Fetching URL 2...");
    const html2 = await fetchHtml(url2);
    fs.writeFileSync('server2.html', html2);
    console.log("Saved to server2.html, size:", html2.length);
}

main();
