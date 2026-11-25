package io.lightknight.omniread

import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.scraper.ExtensionCatalogEntry
import io.lightknight.omniread.engine.scraper.ExtensionCatalogHelpers
import io.lightknight.omniread.engine.scraper.ExtensionInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionCatalogManagerTest {

    @Test
    fun bundledIndexJsonParsesAndMatchesAllBundledPluginScriptsSha256() {
        val indexFile = File("src/main/assets/extension_catalog/index.json")
        assertTrue("Bundled index.json must exist", indexFile.isFile)

        val index = ExtensionCatalogHelpers.parseIndexJson(indexFile.readText(Charsets.UTF_8))
        assertEquals(1, index.schemaVersion)
        assertEquals(8, index.extensions.size)

        for (entry in index.extensions) {
            assertTrue(entry.id.isNotBlank())
            assertTrue(entry.name.isNotBlank())
            assertTrue(entry.baseUrl.startsWith("https://"))
            assertEquals(64, entry.sha256.length)

            val scriptFile = File("src/main/assets/plugins/${entry.id}.js")
            assertTrue("Missing script asset for ${entry.id}", scriptFile.isFile)
            val scriptContent = scriptFile.readText(Charsets.UTF_8)
            assertTrue(
                "SHA-256 mismatch for ${entry.id}",
                ExtensionCatalogHelpers.verifySha256(scriptContent, entry.sha256)
            )
        }
    }

    @Test
    fun computeInstallStateHandlesAvailableInstalledUpdateAndIncompatible() {
        val entry = ExtensionCatalogEntry(
            id = "docln_hako",
            name = "DocLN",
            mediaType = "NOVEL",
            lang = "vi",
            version = 7,
            minAppApi = 1,
            baseUrl = "https://docln.sbs"
        )

        assertEquals(
            ExtensionInstallState.AVAILABLE,
            ExtensionCatalogHelpers.computeInstallState(entry, installed = null, currentAppApi = 1)
        )

        val sameVersion = SourcePluginEntity(
            id = "docln_hako",
            name = "DocLN",
            mediaType = MediaType.NOVEL,
            version = 7,
            baseUrl = "https://docln.sbs",
            scriptContent = ""
        )
        assertEquals(
            ExtensionInstallState.INSTALLED,
            ExtensionCatalogHelpers.computeInstallState(entry, installed = sameVersion, currentAppApi = 1)
        )

        val olderVersion = sameVersion.copy(version = 6)
        assertEquals(
            ExtensionInstallState.UPDATE_AVAILABLE,
            ExtensionCatalogHelpers.computeInstallState(entry, installed = olderVersion, currentAppApi = 1)
        )

        val newerApiEntry = entry.copy(minAppApi = 2)
        assertEquals(
            ExtensionInstallState.INCOMPATIBLE,
            ExtensionCatalogHelpers.computeInstallState(newerApiEntry, installed = null, currentAppApi = 1)
        )
    }

    @Test
    fun verifySha256NormalizesCrlfAndRejectsTamperedScript() {
        val lfScript = "async function popular(p) {\n  return [];\n}\n"
        val crlfScript = "async function popular(p) {\r\n  return [];\r\n}\r\n"
        val expected = ExtensionCatalogHelpers.computeNormalizedSha256(lfScript)

        assertTrue(ExtensionCatalogHelpers.verifySha256(lfScript, expected))
        assertTrue(ExtensionCatalogHelpers.verifySha256(crlfScript, expected))
        assertFalse(ExtensionCatalogHelpers.verifySha256("$lfScript// tampered", expected))
    }

    @Test
    fun resolveScriptUrlSupportsGithubRawJsdelivrAndExplicitUrls() {
        val rawUrl = ExtensionCatalogHelpers.resolveScriptUrl(
            indexUrl = "https://raw.githubusercontent.com/umefor-labs/umefor-library-extensions/main/index.json",
            scriptPath = "extensions/docln_hako/plugin.js"
        )
        assertEquals(
            "https://raw.githubusercontent.com/umefor-labs/umefor-library-extensions/main/extensions/docln_hako/plugin.js",
            rawUrl
        )

        val jsdelivrUrl = ExtensionCatalogHelpers.resolveScriptUrl(
            indexUrl = "https://cdn.jsdelivr.net/gh/umefor-labs/umefor-library-extensions@main/index.json",
            scriptPath = "extensions/truyenqqko/plugin.js"
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/umefor-labs/umefor-library-extensions@main/extensions/truyenqqko/plugin.js",
            jsdelivrUrl
        )

        val explicit = ExtensionCatalogHelpers.resolveScriptUrl(
            indexUrl = "https://example.com/index.json",
            scriptPath = "ignored.js",
            explicitScriptUrl = "https://cdn.example.com/custom.js"
        )
        assertEquals("https://cdn.example.com/custom.js", explicit)
    }
}
