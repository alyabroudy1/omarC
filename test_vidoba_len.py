import urllib.request
import re

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

print("Found at:", start)
# Find the end of the script
script_block = html[start:]
# Match eval(...)
match = re.search(r'eval\((function\(p,a,c,k,e,d\).+?split\(\s*\'\|\'\s*\)\s*\)\s*\))', script_block, re.DOTALL)
if match:
    print("Matched! Length:", len(match.group(0)))
    print("Ends with:", match.group(0)[-100:])
    print("Group 1 ends with:", match.group(1)[-100:])
else:
    print("No regex match")
