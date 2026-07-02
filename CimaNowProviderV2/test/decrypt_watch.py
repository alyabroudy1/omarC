import re
import base64
import sys

def decrypt_watch_html(html_content):
    # 1. Try to find the _oArr array containing integers to sum up for the key
    oArr_match = re.search(r"var\s+_oArr\s*=\s*\[([\d,\s]+)\]", html_content)
    if oArr_match:
        key = sum(int(x.strip()) for x in oArr_match.group(1).split(",") if x.strip())
        print(f"[*] Found _oArr key array. Calculated XOR Key: {key}")
    else:
        # 2. Try the _dk1 and _dk2 fallback
        dk1_match = re.search(r"var\s+_dk1\s*=\s*(\d+);", html_content)
        dk2_match = re.search(r"var\s+_dk2\s*=\s*(\d+);", html_content)
        if dk1_match and dk2_match:
            key = int(dk1_match.group(1)) - int(dk2_match.group(1))
            print(f"[*] Found _dk1 and _dk2. Calculated XOR Key: {key}")
        else:
            print("[!] Critical: No XOR key source found in watch HTML!")
            return None

    # 3. Find the base64-encoded encrypted variable containing '@'
    var_match = None
    for m in re.finditer(r"var\s+(_[a-zA-Z0-9]{5})\s*=\s*(.*?);", html_content, re.DOTALL):
        var_name = m.group(1)
        val = m.group(2)
        if "@" in val:
            var_match = (var_name, val)
            break

    if not var_match:
        print("[!] Critical: Encrypted base64 variable (containing '@') not found in watch HTML!")
        return None

    var_name, raw_val = var_match
    print(f"[*] Found encrypted variable name: {var_name}")

    # Extract all single-quoted string literals and concatenate
    string_content = "".join(re.findall(r"'([^']*)'", raw_val))

    # Split by '@' and decrypt each segment
    parts = string_content.split('@')
    decrypted_chars = []
    for p in parts:
        if not p.strip():
            continue
        try:
            # Base64 decode, strip non-digits, and XOR with key
            dec_bytes = base64.b64decode(p)
            dec_str = dec_bytes.decode('latin-1', errors='ignore')
            digits = "".join([c for c in dec_str if c.isdigit()])
            if not digits:
                continue
            num = int(digits) ^ key
            decrypted_chars.append(chr(num))
        except Exception as e:
            # Ignore decoding errors for invalid blocks
            pass

    # Convert Latin1/ISO-8859-1 character stream to standard UTF-8 string
    decrypted_raw = "".join(decrypted_chars)
    try:
        decrypted_html = decrypted_raw.encode('latin-1').decode('utf-8', errors='ignore')
    except Exception:
        decrypted_html = decrypted_raw

    return decrypted_html

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 decrypt_watch.py <path_to_watch_html_file>")
        sys.exit(1)
        
    file_path = sys.argv[1]
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        html = f.read()
        
    decrypted = decrypt_watch_html(html)
    if decrypted:
        output_path = file_path + ".decrypted.html"
        with open(output_path, "w", encoding="utf-8") as out:
            out.write(decrypted)
        print(f"[+] Decryption successful! Saved to: {output_path}")
    else:
        print("[-] Decryption failed.")
