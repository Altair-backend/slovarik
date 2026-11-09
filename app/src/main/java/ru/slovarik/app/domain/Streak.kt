package ru.slovarik.app.domain

/** Подсчёт серии дней подряд. days — номера дней (epochDay), в которые были занятия. */
object Streak {

    /** Текущая серия: считается, если занимались сегодня или вчера (сегодняшний день ещё можно успеть). */
    fun current(days: Set<Long>, today: Long): Int {
        var day = if (today in days) today else today - 1
        var count = 0
        while (day in days) {
            count++
            day--
        }
        return count
    }

    /** Самая длинная серия за всё время. */
    fun best(days: Set<Long>): Int {
        var best = 0
        for (day in days) {
            if ((day - 1) in days) continue // не начало серии
            var length = 1
            while ((day + length) in days) length++
            if (length > best) best = length
        }
        return best
    }
}
