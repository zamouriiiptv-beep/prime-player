package com.castivio.feature.home

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every word on a film's page and on a series' page is in every language the application has.
 *
 * The pages draw the provider's words and the application's own. The provider's are what the provider sent; the
 * application's -- the plot's heading, "Director:", "Cast:", the seasons, the buttons, the way a failure is told --
 * are resources, and a language that lacks one shows it in English in the middle of a page that is otherwise its own.
 * That went unnoticed for months because a missing translation is not an error: the build falls back to English and says
 * nothing. This test says it, for the keys of these two pages (`show_*` and `movie_*`), in every `values-*` folder.
 *
 * It reads the resource files themselves, so it runs without a device or a Robolectric and fails the day a key is added to
 * the default file and not to the others -- or a language is added and not given the words.
 */
class FilmAndSeriesLabelsTest {

    /** What one resource folder holds. [duplicates] are names defined twice in the folder, which the build rejects. */
    private class Folder(
        val strings: Map<String, String>,
        val untranslatable: Set<String>,
        val plurals: Map<String, Map<String, String>>,
        val duplicates: List<String>,
    )

    private val res = File("src/main/res")

    private fun read(folder: File): Folder {
        val strings = HashMap<String, String>()
        val untranslatable = HashSet<String>()
        val plurals = HashMap<String, Map<String, String>>()
        val seen = HashSet<String>()
        val duplicates = ArrayList<String>()
        val files = folder.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                val name = node.getAttribute("name")
                when (node.tagName) {
                    "string" -> {
                        if (!seen.add("string/$name")) duplicates += "string/$name"
                        strings[name] = node.textContent
                        if (node.getAttribute("translatable") == "false") untranslatable += name
                    }
                    "plurals" -> {
                        if (!seen.add("plurals/$name")) duplicates += "plurals/$name"
                        val items = node.getElementsByTagName("item")
                        plurals[name] = (0 until items.length).associate {
                            val item = items.item(it) as Element
                            item.getAttribute("quantity") to item.textContent
                        }
                    }
                }
            }
        }
        return Folder(strings, untranslatable, plurals, duplicates)
    }

    private val default = read(File(res, "values"))

    /** The keys of the two pages that are words to translate: the neutral ones (`translatable="false"`) are left out. */
    private val pageKeys = default.strings.keys
        .filter { (it.startsWith("show_") || it.startsWith("movie_")) && it !in default.untranslatable }
        .sorted()

    private val countedKeys = listOf("show_seasons", "show_episodes")

    private val languages: List<File> = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
        .orEmpty()
        .filter { File(it, "strings.xml").exists() }
        .sortedBy { it.name }

    /**
     * Which forms of a counted word a language's whole numbers take, by the language's plural rules. The rest of the
     * rules -- decimals, large round numbers -- are given the same words as `other` where a language has them at all.
     */
    private val forms: Map<String, Set<String>> = run {
        val oneOther = setOf("one", "other")
        val other = setOf("other")
        val slavic = setOf("one", "few", "many", "other")
        val romance = setOf("one", "many", "other")
        mapOf(
            "ar" to setOf("zero", "one", "two", "few", "many", "other"),
            "b+fil" to oneOther, "b+zh+Hans" to other, "b+zh+Hant" to other,
            "bg" to oneOther, "bn" to oneOther,
            "cs" to slavic, "da" to oneOther, "de" to oneOther, "el" to oneOther,
            "es" to romance, "fa" to oneOther, "fi" to oneOther, "fr" to romance,
            "hi" to oneOther, "hr" to setOf("one", "few", "other"), "hu" to oneOther,
            "in" to other, "it" to romance, "ja" to other, "ko" to other, "ms" to other,
            "nb" to oneOther, "nl" to oneOther, "pl" to slavic,
            "pt" to romance, "pt-rPT" to romance, "ro" to setOf("one", "few", "other"),
            "ru" to slavic, "sk" to slavic, "sq" to oneOther, "sr" to setOf("one", "few", "other"),
            "sv" to oneOther, "th" to other, "tr" to oneOther, "uk" to slavic, "ur" to oneOther, "vi" to other,
        )
    }

    private fun code(folder: File) = folder.name.removePrefix("values-")

    private val placeholder = Regex("%(?:\\d+\\$)?[sd]")

    private fun placeholdersOf(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `the two pages have words to translate and the application has languages to give them in`() {
        assertTrue("the default file has the pages' keys: $pageKeys", pageKeys.size >= 25)
        assertTrue("the languages found: ${languages.map { it.name }}", languages.size >= 38)
    }

    @Test
    fun `every language has every word of a film's page and a series' page`() {
        val lacking = languages.mapNotNull { folder ->
            val holds = read(folder).strings.keys
            val missing = pageKeys.filter { it !in holds }
            if (missing.isEmpty()) null else "${code(folder)}: $missing"
        }
        assertEquals("words missing from a language -- they would be shown in English:\n${lacking.joinToString("\n")}", emptyList<String>(), lacking)
    }

    @Test
    fun `every language counts the seasons and the episodes`() {
        val lacking = languages.mapNotNull { folder ->
            val holds = read(folder).plurals.keys
            val missing = countedKeys.filter { it !in holds }
            if (missing.isEmpty()) null else "${code(folder)}: $missing"
        }
        assertEquals("counted words missing from a language:\n${lacking.joinToString("\n")}", emptyList<String>(), lacking)
    }

    @Test
    fun `a word keeps its placeholders in every language, so a date or an episode is never dropped or doubled`() {
        val wrong = ArrayList<String>()
        for (folder in languages) {
            val strings = read(folder).strings
            for (key in pageKeys) {
                val translated = strings[key] ?: continue
                val wanted = placeholdersOf(default.strings.getValue(key))
                if (placeholdersOf(translated) != wanted) wrong += "${code(folder)}/$key: $translated"
            }
        }
        assertEquals("placeholders that differ from the default's:\n${wrong.joinToString("\n")}", emptyList<String>(), wrong)
    }

    @Test
    fun `a counted word has the forms the language's numbers take, and its figure`() {
        val wrong = ArrayList<String>()
        for (folder in languages) {
            val language = code(folder)
            val wanted = forms[language]
            if (wanted == null) {
                wrong += "$language: not in this test's table of plural forms -- add it with the forms its numbers take"
                continue
            }
            val plurals = read(folder).plurals
            for (key in countedKeys) {
                val items = plurals[key] ?: continue
                if (items.keys != wanted) wrong += "$language/$key: has ${items.keys.sorted()}, wants ${wanted.sorted()}"
                // Arabic spells out zero, one and two; every other form of every language carries the figure.
                items.forEach { (quantity, text) ->
                    val spelled = language == "ar" && quantity in setOf("zero", "one", "two")
                    if (!spelled && "%d" !in text) wrong += "$language/$key/$quantity: no %d in \"$text\""
                }
            }
        }
        assertEquals("counted words that are wrong:\n${wrong.joinToString("\n")}", emptyList<String>(), wrong)
    }

    @Test
    fun `no word is defined twice in a language, which the build rejects`() {
        val twice = languages.mapNotNull { folder ->
            val duplicates = read(folder).duplicates
            if (duplicates.isEmpty()) null else "${code(folder)}: $duplicates"
        }
        assertEquals("names defined in two files of one folder:\n${twice.joinToString("\n")}", emptyList<String>(), twice)
    }

    @Test
    fun `a translated word is not the English one left in place`() {
        // A few words are the same in a language on purpose (Cast in Italian and Dutch, Season in the Latin-script ones
        // that borrowed it); a long sentence is never. The test is on the sentences, where a copied English one is a mistake.
        val copied = ArrayList<String>()
        val sentences = listOf("show_preparing", "show_no_episodes_detail", "show_failed_title", "show_failed_detail")
        for (folder in languages) {
            val strings = read(folder).strings
            for (key in sentences) {
                if (strings[key] == default.strings[key]) copied += "${code(folder)}/$key"
            }
        }
        assertEquals("sentences left in English:\n${copied.joinToString("\n")}", emptyList<String>(), copied)
    }
}
