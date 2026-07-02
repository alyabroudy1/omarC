import requests
import urllib.parse
import re
import sys

def verify_rss_search(query_term):
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
    
    # We clean query terms similar to getPostIdBackup logic (removing noise words)
    noise_words = ["فيلم", "مسلسل", "مشاهدة", "تحميل", "كامل", "مترجم", "اون لاين", "اونلاين"]
    cleaned_query = query_term.replace("-", " ")
    for word in noise_words:
        cleaned_query = cleaned_query.replace(word, "").strip()
        
    cleaned_query = re.sub(r'\s+', ' ', cleaned_query)
    print(f"[*] Cleaned Search Query: '{cleaned_query}'")

    search_term = urllib.parse.quote(cleaned_query)
    feed_url = f"https://cimanow.cc/feed/?s={search_term}"
    print(f"[*] Requesting RSS Feed: {feed_url}")
    
    try:
        resp = requests.get(feed_url, headers=headers, timeout=10, verify=False)
        print(f"[*] Response Status Code: {resp.status_code}")
        print(f"[*] Response length: {len(resp.text)} bytes")
        
        items = re.findall(r'<item>.*?</item>', resp.text, re.DOTALL)
        print(f"[+] Found {len(items)} feed items.")
        
        for idx, item in enumerate(items[:5]):
            title = re.search(r'<title>([^<]+)</title>', item)
            link = re.search(r'<link>([^<]+)</link>', item)
            guid = re.search(r'<guid[^>]*>([^<]+)</guid>', item)
            
            print(f"\n  Item {idx+1}:")
            print(f"    Title: {title.group(1) if title else 'N/A'}")
            print(f"    Link: {link.group(1) if link else 'N/A'}")
            print(f"    Guid (permalink): {guid.group(1) if guid else 'N/A'}")
            
    except Exception as e:
        print(f"[!] Error querying RSS feed: {e}")

if __name__ == "__main__":
    query = "تحت السن الحلقة 1"
    if len(sys.argv) > 1:
        query = " ".join(sys.argv[1:])
    verify_rss_search(query)
