package com.castivio.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which keys of a remote change the channel, and when.
 *
 * Key codes are the platform's constants, so what is asserted is what a device really sends. The
 * scenes are the three states of the screen the decision reads -- a picture alone, the controls
 * drawn, a sheet open -- and a locked screen.
 */
class ChannelKeysTest {

    private val pictureAlone = KeyScene(locked = false, panelOpen = false, controlsShown = false)
    private val controlsDrawn = pictureAlone.copy(controlsShown = true)
    private val sheetOpen = pictureAlone.copy(panelOpen = true)
    private val locked = pictureAlone.copy(locked = true)
    private val stopped = pictureAlone.copy(failureShown = true)

    private val next = listOf(
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_DPAD_UP,
    )
    private val previous = listOf(
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyEvent.KEYCODE_DPAD_DOWN,
    )

    @Test
    fun `on the picture alone every channel key steps, up the list and down it`() {
        for (key in next) assertEquals("key $key", ChannelStep.Next, channelStepOf(key, pictureAlone))
        for (key in previous) assertEquals("key $key", ChannelStep.Previous, channelStepOf(key, pictureAlone))
    }

    @Test
    fun `a step is one place`() {
        assertEquals(1, ChannelStep.Next.by)
        assertEquals(-1, ChannelStep.Previous.by)
    }

    @Test
    fun `the remote's own channel keys step whatever is drawn`() {
        for (scene in listOf(pictureAlone, controlsDrawn, sheetOpen)) {
            assertEquals(ChannelStep.Next, channelStepOf(KeyEvent.KEYCODE_CHANNEL_UP, scene))
            assertEquals(ChannelStep.Previous, channelStepOf(KeyEvent.KEYCODE_CHANNEL_DOWN, scene))
        }
    }

    @Test
    fun `the arrows keep walking the controls while they are drawn`() {
        assertNull(channelStepOf(KeyEvent.KEYCODE_DPAD_UP, controlsDrawn))
        assertNull(channelStepOf(KeyEvent.KEYCODE_DPAD_DOWN, controlsDrawn))
    }

    @Test
    fun `the arrows keep walking a sheet while one is open`() {
        assertNull(channelStepOf(KeyEvent.KEYCODE_DPAD_UP, sheetOpen))
        assertNull(channelStepOf(KeyEvent.KEYCODE_DPAD_DOWN, sheetOpen))
    }

    @Test
    fun `the arrows keep walking the failure card's buttons, and the channel keys still move on from a dead channel`() {
        assertNull(channelStepOf(KeyEvent.KEYCODE_DPAD_UP, stopped))
        assertNull(channelStepOf(KeyEvent.KEYCODE_DPAD_DOWN, stopped))
        assertEquals(ChannelStep.Next, channelStepOf(KeyEvent.KEYCODE_CHANNEL_UP, stopped))
        assertEquals(ChannelStep.Previous, channelStepOf(KeyEvent.KEYCODE_CHANNEL_DOWN, stopped))
    }

    @Test
    fun `the keys a sheet may type are not channel keys while it is open`() {
        for (key in listOf(KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_NUMPAD_SUBTRACT)) {
            assertNull("key $key", channelStepOf(key, sheetOpen))
        }
    }

    @Test
    fun `the keys other than the arrows step with the controls drawn too`() {
        assertEquals(ChannelStep.Next, channelStepOf(KeyEvent.KEYCODE_PAGE_UP, controlsDrawn))
        assertEquals(ChannelStep.Previous, channelStepOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, controlsDrawn))
        assertEquals(ChannelStep.Next, channelStepOf(KeyEvent.KEYCODE_PLUS, controlsDrawn))
    }

    @Test
    fun `a locked screen takes no channel key at all`() {
        for (key in next + previous) assertNull("key $key", channelStepOf(key, locked))
    }

    @Test
    fun `every other key is left to the focus`() {
        val others = listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_TAB,
        )
        for (key in others) assertNull("key $key", channelStepOf(key, pictureAlone))
    }
}
