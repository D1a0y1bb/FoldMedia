package com.foldmedia.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class XiaomiMotionPhotoBuilderTest {

    @Test
    fun buildsValidXiaomiMotionPhotoReplacingOldXmpAndAppendingMp4() {
        val fakeJpeg = buildSyntheticJpegWithOldXmp()
        val fakeMp4 = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x02, 0x00,
            1, 2, 3, 4, 5, 6, 7, 8
        )

        val motionPhoto = XiaomiMotionPhotoBuilder.buildMotionPhotoBytes(fakeJpeg, fakeMp4)
        val info = XiaomiMotionPhotoBuilder.inspectMotionPhoto(motionPhoto)

        assertTrue(info.isMotionPhoto)
        assertEquals(fakeMp4.size, info.videoLengthBytes)
        assertTrue(info.trailingSliceStartsWithMp4Ftyp)

        val text = String(motionPhoto, 0, minOf(motionPhoto.size, 4096), StandardCharsets.UTF_8)
        assertTrue(!text.contains("OLD_CONFLICTING_XMP"))
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"${fakeMp4.size}\""))
    }

    private fun buildSyntheticJpegWithOldXmp(): ByteArray {
        val out = ByteArrayOutputStream()
        // SOI
        out.write(0xFF)
        out.write(0xD8)

        // Old XMP APP1 segment to verify clean replacement
        val oldHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
        val oldBody = "<x:xmpmeta>OLD_CONFLICTING_XMP</x:xmpmeta>".toByteArray(StandardCharsets.UTF_8)
        val len = 2 + oldHeader.size + oldBody.size
        out.write(0xFF)
        out.write(0xE1)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(oldHeader)
        out.write(oldBody)

        // SOS + dummy scan data + EOI
        out.write(0xFF)
        out.write(0xDA)
        out.write(byteArrayOf(0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x12, 0x34))
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }
}
