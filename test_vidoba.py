import re
import urllib.request
import urllib.error

def fetch(url, headers):
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as response:
            return response.status, response.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')

print("1. Fetching embed page...")
headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': 'https://larozza.casa/'
}
status, html = fetch('https://vidoba.org/embed-51kirhtklgfy.html', headers)
print(f"Status: {status}")

# Find eval(function(p,a,c,k,e,d)...)
packed_match = re.search(r'eval\((function\(p,a,c,k,e,d\).+?)\n', html)
if not packed_match:
    print("Could not find packed JS")
    exit()

# We can just write a quick JS script to unpack it using node
with open('temp_unpack.js', 'w') as f:
    f.write("const packed = " + packed_match.group(1).replace('eval(', '(') + ";\n")
    f.write("console.log(packed);")

