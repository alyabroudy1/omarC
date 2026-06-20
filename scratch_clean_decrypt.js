const fs = require('fs');

// Read ajax-5.js
const content = fs.readFileSync('C:\\Users\\alyab\\.gemini\\antigravity-ide\\brain\\fc329962-6c61-4897-8508-fcc442615d0a\\ajax-5.js', 'utf8');
const lines = content.split('\n');
const line2 = lines[1];

// Extract the string passed to eval(...)
// The structure is: return eval("(function EjAx...")("G%09%11%05%0B...")
const evalStart = line2.indexOf('eval("');
if (evalStart === -1) {
    console.error("Could not find eval start");
    process.exit(1);
}

// The string passed to eval starts after `eval("`
const codeStart = evalStart + 6;
// Find the closing quote of the eval string.
// Note: the string inside eval has escaped quotes or contains the function.
// Let's locate the ending of the eval call, which is `})")`
const codeEnd = line2.indexOf('})")') + 3; // includes the closing `})`
const ejaxString = line2.substring(codeStart, codeEnd);

console.log("Extracted ejaxString length:", ejaxString.length);
console.log("Start of ejaxString:", ejaxString.substring(0, 100));
console.log("End of ejaxString:", ejaxString.substring(ejaxString.length - 100));

// Now extract the argument passed to EjAx, which is inside `)("G%09%11%05%0B...")`
const argStart = line2.indexOf(')("', codeEnd);
if (argStart === -1) {
    console.error("Could not find argument start");
    process.exit(1);
}
const argQuoteStart = argStart + 3;
const argQuoteEnd = line2.lastIndexOf('")');
const nzHp = line2.substring(argQuoteStart, argQuoteEnd);

console.log("Extracted nzHp length:", nzHp.length);
console.log("Start of nzHp:", nzHp.substring(0, 100));

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

// Compute key using the EXACT ejaxString
console.log("Computing decryption key...");
const key = HOrn(ejaxString);
console.log("Decryption key:", key);

// Decrypt nzHp using the computed key
console.log("Decrypting payload...");
const decrypted = zGOn(nzHp, key);
console.log("Decrypted payload size:", decrypted.length);

fs.writeFileSync('temp_deobfuscated_clean.js', decrypted, 'utf8');
console.log("Success! Written to temp_deobfuscated_clean.js");
