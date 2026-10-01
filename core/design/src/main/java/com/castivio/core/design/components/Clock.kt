package com.castivio.core.design.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.castivio.core.design.theme.CastivioTheme
import java.text.DateFormat
import java.util.Date

/**
 * The time, and the day under it.
 *
 * ## Why it is here and not on a screen
 *
 * It was Home's, private to `HomeScreen.kt`, and then the activation header wanted the
 * same pair in the same place. Two screens drawing a clock is two clocks: one of them
 * ticks off `ACTION_TIME_TICK` and the other off whatever the second author reached
 * for, one isolates its digits against the bidirectional algorithm and the other
 * discovers that it should have the first time somebody opens it in Arabic. A shared
 * component is declared once, which is a rule in this repository because that has
 * already happened to it twice.
 *
 * ## The sizes are parameters, not a frame
 *
 * Home reads `CastivioMetrics` and the activation screens read a `SourceMetrics` that
 * delegates to one. Taking the two steps directly is what lets both hand over what they
 * already have without this component learning either of their tables — and they are
 * the same two tokens on both sides, the title's step for the time and the chip's for
 * the date.
 *
 * @param fsTime the step the time is drawn at. The title's, on both callers.
 * @param fsDate the step the date is drawn at, a tier quieter. The chip's.
 */
@Composable
fun CastivioClock(
    fsTime: Dp,
    fsDate: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val now = rememberMinute()
    val locale = LocalConfiguration.current

    // Isolated, both of them. A clock and a date are fixed-shape tokens: drawn
    // unisolated in an Arabic composition, `16/09/2026` is reordered into
    // `162026/09/` by the bidirectional algorithm. See [ltrIsolate].
    val time = remember(now, locale) {
        ltrToken(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)))
    }
    val day = remember(now, locale) {
        ltrToken(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now)))
    }

    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            time,
            style = castivioTitleStyle(fsTime),
            color = colors.onBackgroundStrong,
            maxLines = 1,
        )
        Text(
            day,
            style = castivioChipStyle(fsDate),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The wall clock, to the minute, without a timer.
 *
 * `ACTION_TIME_TICK` cannot be declared in a manifest — the platform only delivers it
 * to a receiver registered at runtime, which is exactly the lifetime a
 * `DisposableEffect` has.
 *
 * ## Registered plainly, and that is not an oversight
 *
 * It was `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)`, which reads like
 * the careful choice and is the wrong one here twice over.
 *
 * `ACTION_TIME_TICK` is a *protected* broadcast: the framework declares it, and no
 * application can send it. There is nothing for a not-exported flag to shut out.
 * Android 14's rule that a dynamic receiver must declare its exposure says so itself —
 * it does not apply to a receiver that only listens for system broadcasts, which this
 * is and only this.
 *
 * And the flag is not free. On the API levels with no such flag, `ContextCompat`
 * emulates it by demanding a `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AndroidX
 * declares against the *application* id. A library module's unit-test package is not
 * that application, so it does not have it, and the call throws. Fifty-three tests
 * across three screens failed on it the moment this clock moved into a header four
 * screens share — guarding an impossibility, at the price of the thing being guarded
 * not running at all.
 */
@Composable
fun rememberMinute(): Long {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(from: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}
