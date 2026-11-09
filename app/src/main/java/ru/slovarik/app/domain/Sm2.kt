package ru.slovarik.app.domain

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Интервальное повторение по алгоритму SM-2 (SuperMemo 2).
 *
 * quality — оценка ответа от 0 до 5:
 *  5 — уверенный верный ответ, 4 — верный ответ, 3 — верно, но с ошибками по ходу,
 *  2 и ниже — неверно (карточка возвращается в начало и показывается снова в тот же день).
 */
object Sm2 {
    const val QUALITY_PERFECT = 5
    const val QUALITY_GOOD = 4
    const val QUALITY_HARD = 3
    const val QUALITY_AGAIN = 2
    const val QUALITY_WRONG = 1

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RELEARN_DELAY_MS = 10L * 60 * 1000
    private const val MIN_EASE = 1.3

    data class State(
        val easeFactor: Double = 2.5,
        val intervalDays: Int = 0,
        val repetitions: Int = 0,
    )

    data class Result(val state: State, val dueAt: Long)

    fun review(state: State, quality: Int, now: Long): Result {
        val q = quality.coerceIn(0, 5)
        val newEase = max(MIN_EASE, state.easeFactor + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        return if (q < 3) {
            Result(State(newEase, 0, 0), now + RELEARN_DELAY_MS)
        } else {
            val interval = when (state.repetitions) {
                0 -> 1
                1 -> 6
                else -> max(1, (state.intervalDays * state.easeFactor).roundToInt())
            }
            Result(State(newEase, interval, state.repetitions + 1), now + interval * DAY_MS)
        }
    }

    /** Новый уровень знания 0..5 после ответа. */
    fun nextLevel(level: Int, quality: Int): Int = when {
        quality >= QUALITY_PERFECT -> level + 2
        quality >= QUALITY_HARD -> level + 1
        else -> level - 1
    }.coerceIn(0, 5)
}
