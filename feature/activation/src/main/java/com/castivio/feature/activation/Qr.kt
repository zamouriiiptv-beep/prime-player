package com.castivio.feature.activation

import android.graphics.Bitmap
import android.graphics.Color

/**
 * The QR fixture: a symbol's *appearance*, with no payload behind it.
 *
 * ## What this replaced, and why
 *
 * This file used to hold a real ZXing encoder, and the activation screen called
 * it with the device's MAC address. `design/activation-spec.md` §5.2 forbids
 * exactly that — no MAC in the payload, no device key, no placeholder URL, no
 * transitional pairing scheme — because the QR's one job is to open the Castivio
 * portal with a pairing context, and neither the portal nor the protocol exists.
 *
 * The encoder is gone rather than pointed at something harmless. A working
 * encoder sitting next to an empty payload is an invitation to fill it, and the
 * guarantee wanted here is stronger than "we chose an inoffensive string": there
 * is **nothing in this image to decode**. It is drawn, not encoded — three finder
 * patterns, the timing rows, and a deterministic pseudo-random data field, the
 * same as the mockup draws.
 *
 * ## When the portal exists
 *
 * A real encoder is introduced once, driven by a real payload, and this file is
 * deleted in the same commit. Not extended, not made conditional: two code paths
 * behind one plate is how a fixture reaches a release build.
 *
 * `RGB_565` rather than `ARGB_8888`: two colours, so half the memory buys nothing
 * lost, and on a stick with a gigabyte of RAM every avoided allocation counts.
 * Black on white regardless of theme, because a camera does not care about the
 * palette and inverting it for a dark interface is a well-meaning change that
 * stops it working.
 */
internal fun qrFixtureBitmap(pixels: Int): Bitmap {
    val grid = fixtureGrid()
    val span = MODULES + QUIET_ZONE * 2
    val scale = (pixels / span).coerceAtLeast(1)
    val size = scale * span

    val buffer = IntArray(size * size) { Color.WHITE }
    for (y in 0 until MODULES) {
        for (x in 0 until MODULES) {
            if (!grid[y][x]) continue
            val top = (y + QUIET_ZONE) * scale
            val left = (x + QUIET_ZONE) * scale
            for (dy in 0 until scale) {
                val row = (top + dy) * size + left
                for (dx in 0 until scale) buffer[row + dx] = Color.BLACK
            }
        }
    }

    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        setPixels(buffer, 0, size, 0, 0, size, size)
    }
}

/**
 * The module grid: the furniture a version 1 symbol has, and noise where its data
 * would be.
 *
 * The noise is a fixed linear congruential sequence rather than `Random`, so the
 * fixture is the same on every device and in every screenshot. A fixture that
 * changed between runs would make every visual comparison useless.
 */
private fun fixtureGrid(): Array<BooleanArray> {
    val grid = Array(MODULES) { BooleanArray(MODULES) }

    fun finder(row: Int, column: Int) {
        for (y in 0 until 7) {
            for (x in 0 until 7) {
                val edge = y == 0 || y == 6 || x == 0 || x == 6
                val core = y in 2..4 && x in 2..4
                grid[row + y][column + x] = edge || core
            }
        }
    }
    finder(0, 0)
    finder(0, MODULES - 7)
    finder(MODULES - 7, 0)

    for (i in 8 until MODULES - 8) {
        grid[6][i] = i % 2 == 0
        grid[i][6] = i % 2 == 0
    }
    grid[MODULES - 8][8] = true

    var seed = 0x5EEDL
    fun next(): Double {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        return seed.toDouble() / 0x7fffffff
    }

    fun reserved(y: Int, x: Int) =
        (y < 9 && x < 9) || (y < 9 && x > MODULES - 9) || (y > MODULES - 9 && x < 9) ||
            y == 6 || x == 6

    for (y in 0 until MODULES) {
        for (x in 0 until MODULES) {
            if (!reserved(y, x)) grid[y][x] = next() > 0.48
        }
    }
    return grid
}

/** A version 1 symbol is 21 modules; two of quiet zone on each side. */
private const val MODULES = 21
private const val QUIET_ZONE = 2
