package com.cimanow

import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import org.mozilla.javascript.Context

class CimanowTest {

    @Test
    fun testRhinoDecoder() {
        // Read the actual fetched HTML that broke the old obfuscator
        val htmlFile = File("cimanow_watch.html")
        if (!htmlFile.exists()) {
            println("Test file not found")
            return
        }
        val html = htmlFile.readText()

        // Extract script tags
        // The script is inside <script type="text/javascript" language="Javascript">
        val scriptRegex = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val scriptMatch = scriptRegex.find(html)
        
        assertTrue("Script tag not found", scriptMatch != null)
        
        var jsCode = scriptMatch!!.groupValues[1]

        // Polyfill atob and document.write
        val atobPolyfill = """
            var document = {
                written: "",
                open: function() {},
                write: function(str) { this.written += str; },
                close: function() {}
            };
            
            var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            function atob(input) {
                var str = String(input).replace(/=+$/, '');
                var output = '';
                for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = chars.indexOf(buffer);
                }
                return output;
            }
        """.trimIndent()
        
        // Remove or replace try/catch if there are any that swallow errors, but the script seems clean
        // We will just prepend the polyfill
        jsCode = atobPolyfill + "\n" + jsCode

        val rhino = Context.enter()
        try {
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            
            println("Evaluating JS...")
            rhino.evaluateString(scope, jsCode, "JavaScript", 1, null)
            
            // Extract the written HTML from document
            val documentObj = scope.get("document", scope) as org.mozilla.javascript.NativeObject
            val written = documentObj.get("written", documentObj).toString()
            
            println("Decoded HTML snippet length: \${written.length}")
            println("Decoded HTML preview: \${written.take(200)}")
            
            assertTrue("Decoded HTML should not be empty", written.isNotEmpty())
            assertTrue("Decoded HTML should have tags", written.contains("<ul") || written.contains("<li") || written.contains("<div"))
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            Context.exit()
        }
    }
}
