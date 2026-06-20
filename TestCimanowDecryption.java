import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;

public class TestCimanowDecryption {
    public static void main(String[] args) {
        try {
            System.out.println("Reading cimanow_watch_302.html...");
            String html = new String(Files.readAllBytes(Paths.get("cimanow_watch_302.html")), "UTF-8");

            // 1. Extract _c9896 string
            Pattern cVarPattern = Pattern.compile("var _c9896\\s*=\\s*'([\\s\\S]*?)';");
            Matcher cVarMatcher = cVarPattern.matcher(html);
            if (!cVarMatcher.find()) {
                System.err.println("Failed to find _c9896!");
                System.exit(1);
            }
            String obfuscatedData = cVarMatcher.group(1).replaceAll("[\\r\\n\\t'+\\s]", "");

            // 2. Extract _r formula
            Pattern rPattern = Pattern.compile("var _r\\s*=\\s*([0-9\\s+]+);");
            Matcher rMatcher = rPattern.matcher(html);
            int rValue = 0;
            if (rMatcher.find()) {
                String formula = rMatcher.group(1);
                System.out.println("Formula matched: " + formula);
                String[] parts = formula.split("\\+");
                for (String part : parts) {
                    rValue += Integer.parseInt(part.trim());
                }
            } else {
                System.err.println("Failed to find _r formula!");
                System.exit(1);
            }
            System.out.println("Resolved _r value: " + rValue);

            // 3. Decrypt
            System.out.println("Decrypting...");
            String[] tokens = obfuscatedData.split("~");
            System.out.println("Total parts: " + tokens.length);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                try {
                    // Re-add base64 padding if necessary
                    String padded = token;
                    while (padded.length() % 4 != 0) {
                        padded += "=";
                    }
                    byte[] base64DecodedBytes = Base64.getDecoder().decode(padded);
                    String decodedStr = new String(base64DecodedBytes, "UTF-8");
                    
                    // Remove non-digits
                    String digitsOnly = decodedStr.replaceAll("\\D", "");
                    if (digitsOnly.isEmpty()) continue;
                    
                    int num = Integer.parseInt(digitsOnly) - rValue;
                    outputStream.write(num);
                } catch (Exception e) {
                    System.err.println("Error decoding token: " + token + " - " + e.getMessage());
                }
            }

            byte[] decryptedBytes = outputStream.toByteArray();
            String decryptedHtml = new String(decryptedBytes, "UTF-8");

            Files.write(Paths.get("cimanow_watch_decrypted_java.html"), decryptedHtml.getBytes("UTF-8"));
            System.out.println("Decrypted HTML saved. Length: " + decryptedHtml.length());

            // Check if matches the JS decrypted file
            if (Files.exists(Paths.get("cimanow_watch_decrypted.html"))) {
                byte[] jsBytes = Files.readAllBytes(Paths.get("cimanow_watch_decrypted.html"));
                boolean match = java.util.Arrays.equals(jsBytes, decryptedBytes);
                System.out.println("Matches JS output exactly? " + match);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
