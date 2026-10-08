package com.cloudstream.shared.webview

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Wave 4c acceptance criterion 2: no file under `shared/` names a specific site.
 *
 * A site name in `shared` is not a cosmetic problem — it is the marker of the coupling Wave 4c
 * removed. `NavigationEngine`, its step/result types, its session policy and `CimaNowTVEmbed` all
 * had exactly one consumer, and while they sat in `shared` every other plugin compiled them.
 * A grep is the only thing that keeps them out, so the grep is a test.
 *
 * Walks the tree from the repo root, which is found by climbing from the test's working directory
 * (the Gradle module dir) until `settings.gradle.kts` appears. If no root is found the test
 * **skips** rather than passing silently — an unlocatable tree is not evidence of anything.
 */
class SharedHasNoSiteNamesTest {

    private val forbidden = listOf("cimanow", "freex")

    @Test
    fun sharedNamesNoSite() {
        val root = repoRoot()
        assumeTrue("Could not locate the repo root (no settings.gradle.kts above ${File("").absolutePath})",
            root != null)
        val sharedMain = File(root, "shared/src/main")
        assumeTrue("shared/src/main not found under ${root?.absolutePath}", sharedMain.isDirectory)

        val offenders = mutableListOf<String>()
        sharedMain.walkTopDown().filter { it.isFile }.forEach { f ->
            val text = try { f.readText() } catch (_: Exception) { return@forEach }
            val lower = text.lowercase()
            forbidden.filter { lower.contains(it) }.forEach { word ->
                val line = text.lines().indexOfFirst { it.lowercase().contains(word) } + 1
                offenders += "${f.relativeTo(sharedMain).path}:$line names '$word'"
            }
        }
        assertTrue("shared/ must not name a specific site:\n" + offenders.joinToString("\n"),
            offenders.isEmpty())
    }

    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
