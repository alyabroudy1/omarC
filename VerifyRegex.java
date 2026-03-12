import java.util.regex.Pattern;

public class VerifyRegex {
    public static void main(String[] args) {
        String lookupFuncName = "_0x4228";
        // This is what I have currently:
        String faultyRegex = "function (_0x[a-f0-9]+)\\([^)]+\\)\\{return " + lookupFuncName + "\\([^}]+}";
        
        try {
            System.out.println("Compiling faulty regex: " + faultyRegex);
            Pattern.compile(faultyRegex);
            System.out.println("Success!");
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
        }

        // Potential fix 1: Escape braces
        String fixedRegex1 = "function (_0x[a-f0-9]+)\\([^)]+\\)\\{return " + lookupFuncName + "\\([^}]+\\}";
        try {
            System.out.println("Compiling fixed regex 1: " + fixedRegex1);
            Pattern.compile(fixedRegex1);
            System.out.println("Success!");
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
        }

        // Potential fix 2: Double escape braces for non-raw string
        String fixedRegex2 = "function (_0x[a-f0-9]+)\\([^)]+\\)\\{return " + lookupFuncName + "\\([^}]+\\}";
         try {
            System.out.println("Compiling fixed regex 2: " + fixedRegex2);
            Pattern.compile(fixedRegex2);
            System.out.println("Success!");
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
        }
    }
}
