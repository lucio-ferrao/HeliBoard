// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.utils.prefs

/**
 * Suggestions for email fields, made only from the addresses typed in email fields before:
 * whole addresses while typing the part before @, and their domains after @.
 * The addresses stay in the keyboard preferences on the device.
 */
class EmailSuggestions(private val context: Context) {
    private class Entry(var count: Int, var lastUsed: Long)

    private val history: MutableMap<String, Entry> by lazy { load() }

    /** Remember the addresses in the text of an email field. */
    fun learn(text: CharSequence) {
        val found = EMAIL.findAll(text).map { it.value.lowercase() }.toSet()
        if (found.isEmpty()) return
        val now = System.currentTimeMillis()
        found.forEach { email ->
            val entry = history.getOrPut(email) { Entry(0, now) }
            entry.count++
            entry.lastUsed = now
        }
        if (history.size > MAX_ADDRESSES)
            history.entries.sortedBy { it.value.lastUsed }.take(history.size - MAX_ADDRESSES).forEach { history.remove(it.key) }
        save()
    }

    /** The address being typed: everything after the last separator before the cursor. */
    fun currentToken(textBeforeCursor: CharSequence): String =
        textBeforeCursor.toString().substringAfterLast(' ').substringAfterLast(',').substringAfterLast(';')
            .substringAfterLast('<').substringAfterLast('\n')

    fun suggestions(token: String): List<String> {
        val ranked = history.entries.sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.count }
            .thenByDescending { it.value.lastUsed }).map { it.key }
        val lower = token.lowercase()
        val result = LinkedHashSet<String>()
        ranked.filterTo(result) { it.startsWith(lower) && it != lower }
        val at = lower.indexOf('@')
        if (at >= 0) {
            val local = token.substring(0, at)
            val domainStart = lower.substring(at + 1)
            ranked.map { it.substringAfter('@') }.distinct()
                .filter { it.startsWith(domainStart) && it != domainStart }
                .forEach { result.add("$local@$it") }
        }
        return result.take(MAX_SUGGESTIONS)
    }

    fun toSuggestedWords(emails: List<String>): SuggestedWords {
        val infos = ArrayList<SuggestedWordInfo>()
        emails.forEachIndexed { i, email ->
            infos.add(SuggestedWordInfo(email, "", Int.MAX_VALUE - i, SuggestedWordInfo.KIND_COMPLETION,
                Dictionary.DICTIONARY_HARDCODED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE))
        }
        return SuggestedWords(infos, null, null, false, false, false, InputStyle.NONE, SuggestedWords.NOT_A_SEQUENCE_NUMBER)
    }

    private fun load(): MutableMap<String, Entry> {
        val map = HashMap<String, Entry>()
        context.prefs().getString(PREF_EMAIL_HISTORY, "").orEmpty().lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) map[parts[0]] = Entry(parts[1].toIntOrNull() ?: 1, parts[2].toLongOrNull() ?: 0)
        }
        return map
    }

    private fun save() {
        context.prefs().edit {
            putString(PREF_EMAIL_HISTORY, history.entries.joinToString("\n") { "${it.key}\t${it.value.count}\t${it.value.lastUsed}" })
        }
    }

    companion object {
        private const val PREF_EMAIL_HISTORY = "email_history"
        private const val MAX_ADDRESSES = 200
        private const val MAX_SUGGESTIONS = 5
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    }
}
