import org.jsoup.Jsoup
import java.io.File
import java.net.URI

fun main() {
    val html = File("/Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2/omarC/yalla_test.html").readText()
    val doc = Jsoup.parse(html)
    
    // Test the extraction logic
    var foundLinks = false
    
    val iframeElement = doc.selectFirst(".entry-content iframe, .posts-body iframe, iframe.cf")
    val iframeSrc = iframeElement?.attr("src")?.trim()
    
    if (iframeSrc.isNullOrBlank()) {
        println("FAILED: No iframe found.")
        return
    }
    
    println("SUCCESS: Found iframe SRC -> \$iframeSrc")
    
    // Simulate Alba extraction text
    val host = try { URI(iframeSrc).host } catch(e: Exception) { "" }
    println("Simulated Host -> \$host")
    
    // Check video-serv
    val videoServs = doc.select(".video-serv a")
    println("Found \${videoServs.size} external video-serv selectors")
    
    println("Extraction logic validated.")
}
