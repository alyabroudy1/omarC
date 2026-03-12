import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TestRhino {
    public static void main(String[] args) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("/tmp/token1_full.html")));
        String[] lines = html.split("\n");
        String targetLine = null;
        for (String l : lines) {
            if (l.contains("_0x") && (l.contains("document['write']") || l.contains("document.write") || l.contains("hlsPlaylist"))) {
                targetLine = l;
                break;
            }
        }
        if (targetLine == null) {
            System.out.println("Line not found");
            return;
        }
        
        String js = targetLine;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<script>(.*?)</script>").matcher(targetLine);
        if (m.find()) {
            js = m.group(1);
        }
        
        System.out.println("Initial JS length: " + js.length());
        
        // Find mainPlayer split
        int setupIdx = js.indexOf("mainPlayer[");
        if (setupIdx > 0) {
            js = js.substring(0, setupIdx);
        }
        
        // Find array func
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("function(_0x[a-f0-9]+)\\(\\)\\{.*?return _0x[a-f0-9]+;\\}").matcher(js);
        String arrayFunc = m2.find() ? m2.group() : "";
        System.out.println("Array func found: " + !arrayFunc.isEmpty());

        String[] jsParts = js.split("function(_0x[a-f0-9]+)\\(\\)\\{.*?return _0x[a-f0-9]+;\\}");
        String afterArray = jsParts.length > 1 ? jsParts[1] : "";
        
        java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("!function\\(.*?\\(.*?\\).*?\\((.*?)\\).*?\\{").matcher(afterArray);
        String shufflerFunc = m3.find() ? m3.group() : "";
        
        // Compile regex for shuffler
        java.util.regex.Matcher shufflerMatcher = java.util.regex.Pattern.compile("!function\\([^)]+\\)\\{.*?\\}(.*?\\((.*?)\\));").matcher(js);
        if (shufflerMatcher.find()) {
            System.out.println("Found shuffler");
        }
        
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_1_8);
            Scriptable scope = cx.initStandardObjects();
            
            // just compile the whole JS up to mainPlayer to see what throws
            System.out.println("Trying to evaluate " + js.length() + " bytes");
            cx.evaluateString(scope, js, "test", 1, null);
            System.out.println("Evaluation succeeded!");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Context.exit();
        }
    }
}
