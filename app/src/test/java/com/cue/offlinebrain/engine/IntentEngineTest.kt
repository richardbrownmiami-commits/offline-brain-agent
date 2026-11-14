package com.cue.offlinebrain.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class IntentEngineTest {
    private val engine = IntentEngine()
    @Test fun routesWeatherLocally() { assertEquals("weather", engine.classify("weather in Tokyo").name) }
    @Test fun customPhrasesAreEditableTriggers() { assertEquals("custom", engine.classify("launch my reading routine", "reading routine").name) }
    @Test fun routesMacroTapAndTypeStepsToAccessibility() {
        assertEquals("open_app", engine.classify("Open WhatsApp").name)
        assertEquals("tap", engine.classify("Tap Search").name)
        assertEquals("tap", engine.classify("Type Mom").name)
    }
    @Test fun extractsAlarmTime() {
        val now = Calendar.getInstance().apply { set(2025, Calendar.JANUARY, 2, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val result = engine.classify("set an alarm for 7:30 pm tomorrow", nowMillis = now)
        assertEquals("alarm", result.name)
        val then = Calendar.getInstance().apply { timeInMillis = result.alarm!!.triggerAtMillis }
        assertEquals(19, then.get(Calendar.HOUR_OF_DAY)); assertEquals(30, then.get(Calendar.MINUTE)); assertEquals(3, then.get(Calendar.DAY_OF_MONTH))
    }
    @Test fun routesWakeMeUpToAnAlarm() {
        val now = Calendar.getInstance().apply { set(2025, Calendar.JANUARY, 2, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val result = engine.classify("wake me up at 6 am tomorrow", nowMillis = now)
        assertEquals("alarm", result.name)
        assertNotNull(result.alarm)
        val then = Calendar.getInstance().apply { timeInMillis = result.alarm!!.triggerAtMillis }
        assertEquals(6, then.get(Calendar.HOUR_OF_DAY)); assertEquals(3, then.get(Calendar.DAY_OF_MONTH))
    }
    @Test fun tokenizesAccentsAndPunctuation() { assertEquals(listOf("cafe", "news"), engine.tokenize("Café, news!")) }
}
