package com.movo.customer.parcel.ui

import kotlin.math.pow

/** WCAG 2 relative luminance of sRGB colors, excluding alpha. */
fun contrastRatio(first: Int, second: Int): Double {
    fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((rgb shr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    val a = luminance(first); val b = luminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
