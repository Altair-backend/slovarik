package ru.slovarik.app.util

import java.time.LocalDate
import java.time.ZoneId

object Time {
    fun today(): Long = LocalDate.now().toEpochDay()

    /** Последняя миллисекунда сегодняшнего дня: всё, что нужно повторить до неё, — «на сегодня». */
    fun endOfToday(): Long =
        LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val tenths = (ms % 1000) / 100
        return "%d:%02d.%d".format(totalSeconds / 60, totalSeconds % 60, tenths)
    }
}

/** Русское склонение: plural(5, "слово", "слова", "слов") -> "слов". */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

fun wordsCount(n: Int): String = "$n ${plural(n, "слово", "слова", "слов")}"
fun cardsCount(n: Int): String = "$n ${plural(n, "карточка", "карточки", "карточек")}"
fun daysCount(n: Int): String = "$n ${plural(n, "день", "дня", "дней")}"
