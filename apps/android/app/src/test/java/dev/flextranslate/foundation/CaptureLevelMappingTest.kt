package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фиксирует dBFS-маппинг уровня микрофона в [AudioCaptureController.peakToLevelPercent]. На ревью
 * WS0–WS4 всплыло, что старый линейный индикатор `rms/Short.MAX_VALUE` показывал почти ноль для
 * обычной речи; эти проверки прибивают перцептивные пол и потолок и доказывают, что типичные пики
 * речи заполняют хорошо заметную часть шкалы.
 */
class CaptureLevelMappingTest {

    @Test
    fun `silence maps to zero`() {
        assertEquals(0, AudioCaptureController.peakToLevelPercent(0))
    }

    @Test
    fun `full scale maps to one hundred`() {
        assertEquals(100, AudioCaptureController.peakToLevelPercent(32_767))
    }

    @Test
    fun `normal speech peak fills a visible portion of the bar`() {
        // ~−12 dBFS — комфортный пик при разговоре (8192 из 32768 full scale).
        val level = AudioCaptureController.peakToLevelPercent(8_192)
        assertTrue("speech should be clearly visible, was $level%", level in 60..95)
    }

    @Test
    fun `mapping is monotonic non-decreasing in peak`() {
        var previous = -1
        for (peak in intArrayOf(0, 64, 256, 1_024, 4_096, 8_192, 16_384, 32_767)) {
            val level = AudioCaptureController.peakToLevelPercent(peak)
            assertTrue("level must not decrease as peak rises", level >= previous)
            previous = level
        }
    }
}
