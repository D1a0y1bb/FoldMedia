package com.foldmedia.app.engine

import com.foldmedia.app.model.VideoSubtitleRemoveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpEngineTest {

    @Test
    fun buildsBoundedSubtitleRemovalFiltersAcrossOrientations() {
        assertNull(YtDlpEngine.buildSubtitleRemovalFilter(1080, 1920, VideoSubtitleRemoveMode.OFF))
        assertNull(YtDlpEngine.buildSubtitleRemovalFilter(1080, 1920, VideoSubtitleRemoveMode.SOFT_STRIP))
        assertNull(YtDlpEngine.buildSubtitleRemovalFilter(32, 32, VideoSubtitleRemoveMode.DELOGO_BOTTOM))

        val portraitDelogo = YtDlpEngine.buildSubtitleRemovalFilter(1080, 1920, VideoSubtitleRemoveMode.DELOGO_BOTTOM)
        assertNotNull(portraitDelogo)
        assertTrue(portraitDelogo!!.startsWith("delogo="))

        val landscape4kDelogo = YtDlpEngine.buildSubtitleRemovalFilter(3840, 2160, VideoSubtitleRemoveMode.DELOGO_BOTTOM)
        assertEquals("delogo=x=307:y=1814:w=3226:h=342", landscape4kDelogo)

        val oddHeightCrop = YtDlpEngine.buildSubtitleRemovalFilter(720, 1281, VideoSubtitleRemoveMode.CROP_BOTTOM)
        assertEquals("crop=iw:1100:0:0", oddHeightCrop)
    }
}
