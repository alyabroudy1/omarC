import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;

public class TestCimanowDecryptionV2 {
    public static void main(String[] args) {
        try {
            System.out.println("Reading temp_watch_guy.html...");
            String html = new String(Files.readAllBytes(Paths.get("temp_watch_guy.html")), "UTF-8");

            // 1. Extract obfuscated base64 chunks
            System.out.println("Extracting obfuscated data chunks...");
            Pattern chunkPattern = Pattern.compile("['\"](O(?:[A-Za-z0-9+/=]+~?)+)['\"]");
            Matcher chunkMatcher = chunkPattern.matcher(html);
            StringBuilder sb = new StringBuilder();
            int chunkCount = 0;
            while (chunkMatcher.find()) {
                sb.append(chunkMatcher.group(1));
                chunkCount++;
            }
            System.out.println("Found " + chunkCount + " chunks.");
            String obfuscatedData = sb.toString().replaceAll("[\\r\\n\\t'+\\s]", "");
            System.out.println("Total obfuscatedData length: " + obfuscatedData.length());

            // 2. Extract _r formula
            Pattern rPattern = Pattern.compile("var\\s+_r\\s*=\\s*([\\d\\s+\\-*\\/()]+);");
            Matcher rMatcher = rPattern.matcher(html);
            int rValue = 0;
            if (rMatcher.find()) {
                String formula = rMatcher.group(1);
                System.out.println("Formula matched: " + formula);
                // Simple parser/evaluator for sum
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
            System.out.println("Total parts/tokens: " + tokens.length);

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

            Files.write(Paths.get("temp_guy_decrypted_java_v2.html"), decryptedHtml.getBytes("UTF-8"));
            System.out.println("Decrypted HTML saved. Length: " + decryptedHtml.length());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
