// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a release states about the app, held to what the app is.
 *
 * The README, the privacy policy, the store text and the F-Droid metadata each
 * restate facts the code owns: its permissions, the services it talks to, its
 * version. Each of these fails as soon as one of them drifts, rather than when
 * somebody happens to reread it.
 */
class ReleaseReadinessTest {

    private val root = File("..").canonicalFile
    private fun read(path: String) = File(root, path).readText()

    @Test
    fun everyPermissionIsExplained() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        val permissions = Regex("""uses-permission android:name="android\.permission\.(\w+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no permissions found", permissions.isNotEmpty())
        val missing = EXPLAINED_IN.flatMap { doc ->
            val text = read(doc)
            permissions.filter { "`$it`" !in text }.map { "$it in $doc" }
        }
        assertTrue("Permissions not explained: $missing", missing.isEmpty())
    }

    @Test
    fun everyServiceIsDisclosedAndCredited() {
        val hosts = sources("data/src/main")
            .flatMap {
                Regex("""https://([a-z0-9.-]+)""").findAll(it.readText()).map { m ->
                    m.groupValues[1]
                }
            }
            .filterNot { it == CONTACT_HOST }
            .toSet()
        val unknown = hosts.filter { host ->
            SERVICES.keys.none {
                host == it ||
                    host.endsWith(".$it")
            }
        }
        assertTrue(
            "Hosts with no disclosure: $unknown. Add each to the privacy policy, README, store " +
                "text and About credits, then to SERVICES here.",
            unknown.isEmpty(),
        )

        val credits = sources("data/src/main").joinToString("\n") { it.readText() }
            .let {
                Regex("""ATTRIBUTION =\s*"([^"]+)"""").findAll(it).map { m ->
                    m.groupValues[1]
                }.toList()
            }
        val missing = SERVICES.filterKeys { domain ->
            hosts.any {
                it == domain ||
                    it.endsWith(".$domain")
            }
        }
            .values.flatMap { name ->
                DISCLOSED_IN.filter { name !in read(it) }.map { "$name in $it" } +
                    listOfNotNull(
                        "$name in the About credits".takeIf {
                            credits.none { c ->
                                name in
                                    c
                            }
                        },
                    )
            }
        assertTrue("Services not disclosed: $missing", missing.isEmpty())
    }

    @Test
    fun storeTextFitsTheStores() {
        val dir = "fastlane/metadata/android/en-US"
        val short = read("$dir/short_description.txt").trim()
        assertTrue("short description over 80: ${short.length}", short.length < SHORT_LIMIT)
        assertTrue("short description ends with a full stop", !short.endsWith("."))
        val full = read("$dir/full_description.txt").trim()
        assertTrue("full description over 4000: ${full.length}", full.length <= FULL_LIMIT)
        val changelog = File(root, "$dir/changelogs/${version().code}.txt")
        assertTrue("no changelog for versionCode ${version().code}", changelog.exists())
        assertTrue("changelog over 500", changelog.readText().trim().length <= CHANGELOG_LIMIT)
    }

    @Test
    fun theVersionAgreesEverywhere() {
        val (code, name) = version()
        val fdroid = read("fdroid/lv.bolwarra.wetter.yml")
        val expected = listOf(
            "versionName: $name",
            "versionCode: $code",
            "commit: v$name",
            "CurrentVersion: $name",
            "CurrentVersionCode: $code",
        )
        val missing = expected.filter { it !in fdroid }
        assertTrue("F-Droid metadata disagrees: $missing", missing.isEmpty())
        assertTrue("CHANGELOG.md has no entry for $name", "## [$name]" in read("CHANGELOG.md"))
    }

    @Test
    fun everySourceFileCarriesItsLicence() {
        val files = MODULES.flatMap { sources(it, "kt", "kts") } +
            root.listFiles { f -> f.extension == "kts" }.orEmpty()
        val unlicensed = files.filterNot { file ->
            file.useLines { lines -> lines.take(3).any { LICENCE in it } }
        }.map { it.relativeTo(root).path }
        assertTrue("No licence header: $unlicensed", unlicensed.isEmpty())
    }

    @Test
    fun everyDocumentMentionedExists() {
        val published = (
            File(root, "docs").listFiles().orEmpty().toList() +
                PUBLIC_ROOT_DOCS.map { File(root, it) }
            )
            .filter { it.exists() }
            .map { it.name }
            .toSet()
        val texts = MODULES.flatMap { sources(it, "kt", "kts", "xml") } +
            File(root, "docs").listFiles().orEmpty().filter { it.extension == "md" } +
            PUBLIC_ROOT_DOCS.map { File(root, it) }.filter { it.exists() }
        val dangling = texts.flatMap { file ->
            Regex("""([\w./-]+)\.md\b""").findAll(file.readText())
                .map { it.value.substringAfterLast('/') }
                .filter { it !in published }
                .map { "$it in ${file.relativeTo(root).path}" }
        }.distinct()
        assertTrue("Documents mentioned but not published: $dangling", dangling.isEmpty())
    }

    private data class Version(val code: Int, val name: String)

    private fun version(): Version {
        val build = read("app/build.gradle.kts")
        val code = Regex("""versionCode = (\d+)""").find(build)!!.groupValues[1].toInt()
        val name = Regex("""versionName = "([^"]+)"""").find(build)!!.groupValues[1]
        return Version(code, name)
    }

    private fun sources(dir: String, vararg extensions: String = arrayOf("kt")): List<File> =
        File(root, dir).walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension in extensions }
            .toList()

    private companion object {
        val MODULES = listOf("app", "data", "domain")
        val EXPLAINED_IN = listOf("README.md", "docs/privacy-policy.md")
        val DISCLOSED_IN = listOf(
            "README.md",
            "docs/privacy-policy.md",
            "fastlane/metadata/android/en-US/full_description.txt",
        )
        val PUBLIC_ROOT_DOCS = listOf("README.md", "CHANGELOG.md", ".github/CONTRIBUTING.md")

        /** Every service the app contacts, by domain, as each document names it. */
        val SERVICES = mapOf(
            "open-meteo.com" to "Open-Meteo",
            "met.no" to "MET Norway",
            "rainviewer.com" to "RainViewer",
            "aviationweather.gov" to "Aviation Weather Center",
            "komoot.io" to "Photon",
            "openstreetmap.org" to "OpenStreetMap",
        )

        /** Named in the User-Agent as the contact; never requested. */
        const val CONTACT_HOST = "github.com"

        const val LICENCE = "SPDX-License-Identifier: GPL-3.0-or-later"
        const val SHORT_LIMIT = 80
        const val FULL_LIMIT = 4000
        const val CHANGELOG_LIMIT = 500
    }
}
