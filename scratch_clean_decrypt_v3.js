const fs = require('fs');

const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

const evalIndex = line2.indexOf('eval(');
const lastQuoteIndex = line2.lastIndexOf('")');
const stringLiteralContent = line2.substring(evalIndex + 5, lastQuoteIndex + 1);
const unescapedStringVal = eval(stringLiteralContent);

const payloadIdx = unescapedStringVal.indexOf('G%09%11%05%0B');
console.log("payloadIdx:", payloadIdx);

// Extract EjAx source code
// It starts at index 1 (after the open parenthesis '(')
// and ends at payloadIdx - 3 (which is the closing brace '}')
// Let's verify by printing the last 20 chars of the extracted function code.
const ejaxSource = unescapedStringVal.substring(1, payloadIdx - 3);
console.log("ejaxSource length:", ejaxSource.length);
console.log("Starts with:", ejaxSource.substring(0, 50));
console.log("Ends with:", ejaxSource.substring(ejaxSource.length - 50));

// Extract payload nzHp
// It starts at payloadIdx and ends at the closing quote (which is the last character of unescapedStringVal - 2, since the last characters are `")`)
// Let's check:
const payloadEndIdx = unescapedStringVal.length - 2;
const nzHp = unescapedStringVal.substring(payloadIdx, payloadEndIdx);
console.log("nzHp length:", nzHp.length);
console.log("nzHp ends with:", nzHp.substring(nzHp.length - 50));

// Define HOrn and zGOn exactly as they are in ajax-5.js
function HOrn(jmun){
    let DJmn=1707600048;
    var fhpn=(0o200776-0x101ED);
    {
        let zEhn;
        while(fhpn<(0o400156%65573)){
            switch(fhpn){
                case (0o400100%65556):
                    fhpn=(0x10258-0o201111);
                    {
                        DJmn^=(jmun.charCodeAt(zEhn)*(0x2935494a%7)+jmun.charCodeAt(zEhn>>>(0x5E30A78-0O570605164)))^662565034;
                    }
                    break;
                case (0o400077%65560):
                    fhpn=(0x4009B%0o200036);
                    zEhn++;
                    break;
                case (0x400AB%0o200042):
                    fhpn=zEhn<jmun.length?(0o204160-67672):(0o1000260%65571);
                    break;
                case (0o400101%0x10018):
                    fhpn=(0o1000243%0x10020);
                    zEhn=(0x21786%3);
                    break;
            }
        }
    }
    let bckn="";
    var beRn=(67516-0o203633);
    {
        let DLTn;
        while(beRn<(0x10618-0o202761)){
            switch(beRn){
                case (0o205120-68143):
                    beRn=(0o202570-0x10564);
                    DLTn=(0x21786%3);
                    break;
                case (262228%0o200020):
                    beRn=DLTn<(0O3153050563-0x19AC516B)?(66676-0o202116):(0o201414-0x102E5);
                    break;
                case (0x108E8-0o204302):
                    beRn=(0x20033%0o200024);
                    {
                        const X8Ln=DJmn%(68176-0o205072);
                        DJmn=Math.floor(DJmn/(0o203674-0x107A6));
                        bckn+=X8Ln>=(0x1071C-0o203402)?String.fromCharCode((0o600404%65601)+(X8Ln-(0o1000136%0x10011))):String.fromCharCode((0o217120-0x11DEF)+X8Ln);
                    }
                    break;
                case (0o201402-66295):
                    beRn=(0o201130-0x10244);
                    DLTn++;
                    break;
            }
        }
    }
    return bckn;
}

function zGOn(T3Gn,vBJn){
    T3Gn=decodeURI(T3Gn);
    let PYBn=(0x21786%3);
    let rwEn="";
    var rybo=(67066-0o202751);
    {
        let T5do;
        while(rybo<(0o600170%0x1001C)){
            switch(rybo){
                case (0o200360-65752):
                    rybo=(0o200454-65821);
                    {
                        rwEn+=String.fromCharCode(T3Gn.charCodeAt(T5do)^vBJn.charCodeAt(PYBn));
                        PYBn++;
                        var nt6n=(0o201130-66106);
                        while(nt6n<(0x103A2-0o201603)) {
                            switch(nt6n){
                                case (0x104B0-0o202222):
                                    nt6n=PYBn>=vBJn.length?(0o600105%0x10013):(0x300AC%0o200057);
                                    break;
                                case (0o400076%65561):
                                    nt6n=(67706-0o204133);
                                    {
                                        PYBn=(0x21786%3);
                                    }
                                    break;
                            }
                        }
                    }
                    break;
                case (262299%0o200036):
                    rybo=T5do<T3Gn.length?(68176-0o205070):(0o1000274%65574);
                    break;
                case (66726-0o202225):
                    rybo=(67636-0o204021);
                    T5do=(0x21786%3);
                    break;
                case (0o202734-67021):
                    rybo=(262319%0o200043);
                    T5do++;
                    break;
            }
        }
    }
    return rwEn;
}

// Compute key using the EXACT ejaxSource
console.log("Computing decryption key...");
const key = HOrn(ejaxSource);
console.log("Decryption key:", key);

// Decrypt payload using the computed key
console.log("Decrypting payload...");
const decrypted = zGOn(nzHp, key);
console.log("Decrypted payload size:", decrypted.length);

fs.writeFileSync('temp_deobfuscated_clean.js', decrypted, 'utf8');
console.log("Success! Written to temp_deobfuscated_clean.js");
