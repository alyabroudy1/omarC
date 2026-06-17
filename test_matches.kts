import org.jsoup.Jsoup
import java.io.File

fun main() {
    val html = File("/Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2/omarC/shoot.html").readText()
    val doc = Jsoup.parse(html)
    
    val matches = mutableListOf<String>()
    
    doc.select(".AY_Match").forEach { element ->
        val teams = element.select(".TM_Logo img")
        
        if (teams.size >= 2) {
            val rightTeam = teams[0].attr("alt")
            val leftTeam = teams[1].attr("alt")
            
            val resultContainer = element.selectFirst(".MT_Result")
            val result = if (resultContainer != null && resultContainer.children().size >= 3) {
                "\${resultContainer.child(0).text()} - \${resultContainer.child(2).text()}"
            } else "VS"
            
            val title = "\$rightTeam \$result \$leftTeam"
            val url = element.selectFirst("a")?.attr("href")
            val posterUrl1 = teams[0].attr("data-src").ifBlank { teams[0].attr("src") }
            val posterUrl2 = teams[1].attr("data-src").ifBlank { teams[1].attr("src") }
            val matchStatus = element.selectFirst(".MT_Stat")?.text()
            val matchTime = element.selectFirst(".MT_Time")?.text()
            
            matches.add("Title: \$title | URL: \$url | POSTERS: \$posterUrl1 vs \$posterUrl2 | Status: \$matchStatus | Time: \$matchTime")
        } else {
            matches.add("FAILED Teams size: \${teams.size}")
        }
    }
    
    println("Total matches: \${matches.size}")
    matches.forEach { println(it) }
}
