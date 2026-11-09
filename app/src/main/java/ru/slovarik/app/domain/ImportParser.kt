package ru.slovarik.app.domain

/** Разбор текста для массового импорта: одна строка — одна карточка «слово <разделитель> перевод». */
object ImportParser {

    enum class Separator(val title: String) {
        DASH("Тире"),
        TAB("Табуляция"),
        COMMA("Запятая"),
    }

    private val spacedDash = Regex("\\s+[—–-]\\s+")
    private val longDash = Regex("\\s*[—–]\\s*")

    fun parse(text: String, separator: Separator): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { splitLine(it, separator) }
            .toList()

    private fun splitLine(line: String, separator: Separator): Pair<String, String>? {
        val parts: List<String> = when (separator) {
            // сначала ищем тире, окружённое пробелами (чтобы не резать слова с дефисом),
            // затем длинное тире без пробелов и только потом обычный дефис
            Separator.DASH -> when {
                spacedDash.containsMatchIn(line) -> line.split(spacedDash, limit = 2)
                longDash.containsMatchIn(line) -> line.split(longDash, limit = 2)
                else -> line.split("-", limit = 2)
            }
            Separator.TAB -> line.split("\t", limit = 2)
            Separator.COMMA -> line.split(",", limit = 2)
        }
        if (parts.size < 2) return null
        val term = parts[0].trim()
        val definition = parts[1].trim()
        return if (term.isEmpty() || definition.isEmpty()) null else term to definition
    }
}
