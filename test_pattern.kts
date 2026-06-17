import java.io.File

val html = """
        var p2p = false;
        var src = "https://vmre6nax.12703830.net:8443/hls/9cznqhfs9.m3u8?s=AQKZshwXNCcKNPbipSCAMA&e=1775077187";

        var downloaded_total = 0,
--
        //$( document ).ready( function(){
                player = new Clappr.Player({
                        source     : src,
"""

val clapprRegex = Regex("source\\s*:\\s*[\"']([^\"']+)[\"']")
val srcVarRegex = Regex("src\\s*=\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']")
val fallbackRegex = Regex("[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")

val res1 = clapprRegex.find(html)?.groupValues?.get(1)
val res2 = srcVarRegex.find(html)?.groupValues?.get(1)
val res3 = fallbackRegex.find(html)?.groupValues?.get(1)

println("clapprRegex: " + res1)
println("srcVarRegex: " + res2)
println("fallbackRegex: " + res3)
