import java.util.*;
import java.util.regex.*;
import java.nio.file.*;

public class Proof {
    public static void main(String[] args) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("/tmp/token1_full.html")));
        
        // LiteralConcat Proof
        System.out.println("=== LiteralConcat Strategy ===");
        Pattern p = Pattern.compile("(?i)document\\['write'\\]\\((.*?)\\);");
        Matcher m = p.matcher(html);
        if (m.find()) {
            String arg = m.group(1);
            Pattern s = Pattern.compile("'([^']+)'");
            Matcher sm = s.matcher(arg);
            StringBuilder sb = new StringBuilder();
            while (sm.find()) {
                sb.append(sm.group(1));
            }
            String result = sb.toString();
            System.out.println("Joined: " + (result.length() > 50 ? result.substring(0, 50) + "..." : result));
            
            Pattern u = Pattern.compile("data-url=\"([^\"]+)\"");
            Matcher um = u.matcher(result);
            while (um.find()) {
                System.out.println("Found URL: " + um.group(1));
            }
        } else {
            System.out.println("No document.write found");
        }

        // RhinoJS Logic Proof (Regex part)
        System.out.println("\n=== RhinoJS Component Extraction ===");
        String[] lines = html.split("\n");
        String targetLine = null;
        for (String line : lines) {
            if (line.contains("_0x") && (line.contains("document['write']") || line.contains("hlsPlaylist"))) {
                targetLine = line;
                break;
            }
        }
        
        if (targetLine != null) {
            String js = targetLine.replaceAll("(?i)</?script[^>]*>", "").trim();
            int lastSemi = js.lastIndexOf(";<");
            if (lastSemi > 0) js = js.substring(0, lastSemi + 1);
            
            // 1. Array Function
            Pattern arrP = Pattern.compile("function (_0x[a-f0-9]+)\\(\\)\\{var _0x[a-f0-9]+=\\[");
            Matcher arrM = arrP.matcher(js);
            if (arrM.find()) {
                String arrName = arrM.group(1);
                System.out.println("Array Func: " + arrName);
                
                // 2. Lookup Function
                String lookRe = "function (_0x[a-f0-9]+)\\(_0x[a-f0-9]+,_0x[a-f0-9]+\\)\\{var _0x[a-f0-9]+=" + arrName;
                Pattern lookP = Pattern.compile(lookRe);
                Matcher lookM = lookP.matcher(js);
                if (lookM.find()) {
                    String lookName = lookM.group(1);
                    System.out.println("Lookup Func: " + lookName);
                    
                    // 3. Wrappers (The failing part)
                    // This matches what the user's log shows
                    String faultyReg = "function (_0x[a-f0-9]+)\\([^)]+\\)\\{return " + lookName + "\\([^}]+}";
                    System.out.println("Testing faulty regex: " + faultyReg);
                    try {
                        Pattern.compile(faultyReg);
                        System.out.println("Faulty regex compiled? (This might be environment dependent)");
                    } catch (Exception e) {
                        System.err.println("Faulty regex failed as expected: " + e.getMessage());
                    }

                    // The proper fix using raw string equivalent
                    String fixedReg = "function (_0x[a-f0-9]+)\\([^)]+\\)\\{return " + lookName + "\\([^}]+\\}";
                    System.out.println("Testing fixed regex: " + fixedReg);
                    try {
                        Pattern.compile(fixedReg);
                        System.out.println("Fixed regex compiled successfully!");
                    } catch (Exception e) {
                        System.err.println("Fixed regex failed: " + e.getMessage());
                    }
                }
            }
        }
    }
}
