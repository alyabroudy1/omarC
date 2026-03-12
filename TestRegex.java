import java.util.regex.Pattern;

public class TestRegex {
    public static void main(String[] args) {
        String r1 = "function (_0x[a-f0-9]+)\\(\\)\\{var _0x[a-f0-9]+=\\[";
        String r2 = "function (_0x[a-f0-9]+)\\(_0x[a-f0-9]+,_0x[a-f0-9]+\\)\\{var _0x[a-f0-9]+=";
        String r3 = "\\{";
        try {
            System.out.println("Compiling r1...");
            Pattern.compile(r1);
            System.out.println("r1 OK");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            System.out.println("Compiling r2...");
            Pattern.compile(r2);
            System.out.println("r2 OK");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
