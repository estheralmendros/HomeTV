package com.hometv.tv.core.util

import kotlin.math.floor

/** One of a star icon's three looks. */
enum class StarFill { FULL, HALF, EMPTY }

/**
 * A rating from ½ to 5 stars in ½ steps (ratings prd.md FR-1.1), stored as [halves] 1..10 like the
 * server's `stars_x2`. The text ("4,5") comes from [Format.stars]; the words from `app`.
 */
@JvmInline
value class Stars(val halves: Int) {
    init {
        require(halves in 1..10) { "halves out of range: $halves" }
    }

    /** 4.5 for 9 halves. */
    val value: Double get() = halves / 2.0

    /** Always five icons: full ones, then a half one when the value ends in ½, then empty ones. */
    fun fills(): List<StarFill> = fillsOf(this)

    /** One ½ step up (+1) or down (-1), clamped to ½..5. */
    fun step(delta: Int): Stars = Stars((halves + delta).coerceIn(MIN, MAX))

    companion object {
        private const val MIN = 1
        private const val MAX = 10

        /** The nearest ½ (half up: 3.25 → 3½, an average's icons), or null outside ½..5 (or null). */
        fun of(value: Double?): Stars? {
            if (value == null || !value.isFinite() || value < 0.5 || value > 5.0) return null
            return Stars(floor(value * 2 + 0.5).toInt().coerceIn(MIN, MAX))
        }

        /** Five empty icons for null (no rating yet). */
        fun fillsOf(stars: Stars?): List<StarFill> = (1..5).map { i ->
            val halves = stars?.halves ?: 0
            when {
                halves >= 2 * i -> StarFill.FULL
                halves == 2 * i - 1 -> StarFill.HALF
                else -> StarFill.EMPTY
            }
        }

        /** The first value a step from "no rating" gives: ½. */
        fun first(): Stars = Stars(MIN)
    }
}
