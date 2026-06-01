package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the dBFS mic-level mapping behind [AudioCaptureController.peakToLevelPercent]. The WS0–WS4
 * review flagged the old linear `rms/Short.MAX_VALUE` meter as reading near-empty for normal speech;
 * these assertions pin the perceptual floor/ceiling and prove that typical speech peaks fill a
 * clearly visible portion of the bar.
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
        // ~−12 dBFS is a comfortable speaking peak (8192 of 32768 full scale).
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
