import urllib.request
import urllib.error
import re
import execjs # using pyexecjs to unpack

req = urllib.request.Request(
    'https://vidoba.org/embed-51kirhtklgfy.html', 
    headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://larozza.casa/'
    }
)
with urllib.request.urlopen(req) as r:
    html = r.read().decode('utf-8')

start = html.find('eval(function(p,a,c,k,e,d)')
if start == -1:
    print("Not found")
    exit()

script_block = html[start:]
# Match eval(...)
match = re.search(r'eval\(function\(p,a,c,k,e,d\).+?split\(\s*\'\|\'\s*\)\s*\)\s*\)', script_block)
if not match:
    print("No regex match")
    exit()

packed_code = match.group(0)
# Wrap in a self-executing function to return the unpacked string in JS
js_code = f"function unpack() {{ return {packed_code.replace('eval(', '(')}; }}"
ctx = execjs.compile(js_code)
unpacked = ctx.call("unpack")

# Find the M3U8 URL
url_match = re.search(r'file:\s*["\'](https://[^"\']+\.m3u8[^"\']*)["\']', unpacked)
if not url_match:
    print("Could not find M3U8 URL in unpacked script")
    exit()

m3u8_url = url_match.group(1)
print("Extracted M3U8:", m3u8_url)

# Test headers
headers_to_test = [
    # 1. Referer = https://vidoba.org/
    {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org/'
    },
    # 2. Referer = https://vidoba.org
    {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org'
    },
    # 3. Referer = https://vidoba.org/embed-51kirhtklgfy.html
    {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org/embed-51kirhtklgfy.html'
    },
    # 4. Referer = empty / no referer
    {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    },
    # 5. Origin = https://vidoba.org + Referer = https://vidoba.org/
    {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://vidoba.org/',
        'Origin': 'https://vidoba.org'
    }
]

for idx, headers in enumerate(headers_to_test):
    print(f"\n--- Test {idx+1}: Headers: {headers} ---")
    req_m3u8 = urllib.request.Request(m3u8_url, headers=headers)
    try:
        with urllib.request.urlopen(req_m3u8) as resp:
            content = resp.read().decode('utf-8')
            print(f"SUCCESS! Status: {resp.status}")
            print("M3U8 Sample:", content[:150])
    except urllib.error.HTTPError as e:
        print(f"FAILED: {e.code}")
        try:
            print("Response:", e.read().decode('utf-8')[:200])
        except Exception:
            pass
    except Exception as e:
        print(f"FAILED: {e}")
