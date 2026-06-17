import re
html = """
        var p2p = false;
        var src = "https://vmre6nax.12703830.net:8443/hls/9cznqhfs9.m3u8?s=8Ghgw0GUp7UhbOxsSvVg8g&e=1775075741";

        var downloaded_total = 0,
--
        //$( document ).ready( function(){
                player = new Clappr.Player({
                        source     : src,
"""
print("Running regex...")
match1 = re.search(r"source\s*:\s*[\"']([^\"']+)[\"']", html)
match2 = re.search(r"src\s*=\s*[\"']([^\"']+\.m3u8[^\"']*)[\"']", html)
match3 = re.search(r"[\"'](https?://[^\"']+\.m3u8[^\"']*)[\"']", html)
print("1:", match1.group(1) if match1 else None)
print("2:", match2.group(1) if match2 else None)
print("3:", match3.group(1) if match3 else None)
