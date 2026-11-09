package ru.slovarik.app.domain

/** Проверка введённого с клавиатуры ответа. */
object AnswerChecker {

    enum class Verdict { CORRECT, TYPO, WRONG }

    /** Приводит строку к виду для сравнения: без регистра, лишних пробелов и различия ё/е. */
    fun normalize(text: String): String =
        text.trim()
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")

    /**
     * Сравнивает ответ с эталоном. Если в эталоне несколько вариантов через «,», «;» или «/»,
     * засчитывается любой из них. Отличие в один символ считается опечаткой.
     */
    fun check(input: String, expected: String): Verdict {
        val answer = normalize(input)
        if (answer.isEmpty()) return Verdict.WRONG
        val variants = buildList {
            add(normalize(expected))
            expected.split(',', ';', '/').map(::normalize).filter { it.isNotEmpty() }.forEach { add(it) }
            // вариант без пояснений в скобках: «бежать (быстро)» -> «бежать»
            add(normalize(expected.replace(Regex("\\(.*?\\)"), " ")))
        }.distinct().filter { it.isNotEmpty() }

        if (variants.any { it == answer }) return Verdict.CORRECT
        val typo = variants.any { it.length >= 4 && levenshtein(answer, it) == 1 }
        return if (typo) Verdict.TYPO else Verdict.WRONG
    }

    /** Расстояние Левенштейна: минимальное число вставок, удалений и замен символов. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
