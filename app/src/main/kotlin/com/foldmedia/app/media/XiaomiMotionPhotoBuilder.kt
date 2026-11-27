package com.foldmedia.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Packs a still image + short MP4 clip into an Android / Xiaomi HyperOS Motion Photo
 * (`MVIMG_...jpg`) compliant with Google `Container:Directory` & `GCamera:MotionPhoto` XMP specs.
 */
object XiaomiMotionPhotoBuilder {

    private val XMP_NAMESPACE_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Ensures raw image bytes (which from Douyin/Xiaohongshu may be WebP or PNG) are converted to JPEG
     * before embedding Motion Photo XMP & trailing MP4 bytes.
     */
    fun ensureJpegBytes(rawImageBytes: ByteArray, quality: Int = 96): ByteArray {
        if (isJpeg(rawImageBytes)) return rawImageBytes
        val bitmap = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.size)
            ?: throw IllegalArgumentException("无法解码实况静态图，无法转换为 JPEG")
        return try {
            val out = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(75, 100), out)
            if (!ok) throw IllegalStateException("静态图转码 JPEG 失败")
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
    }

    /**
     * Pure-Kotlin Motion Photo binary builder:
     * `[JPEG SOI][APP1 XMP Container:Directory + GCamera:MotionPhoto][JPEG body][MP4 bytes]`
     */
    fun buildMotionPhotoBytes(jpegBytes: ByteArray, mp4Bytes: ByteArray): ByteArray {
        require(isJpeg(jpegBytes)) { "输入静态图必须为有效 JPEG 文件" }
        require(mp4Bytes.isNotEmpty()) { "实况视频流不能为空" }

        val xmpXml = buildMotionPhotoXmp(mp4SizeBytes = mp4Bytes.size)
        val xmpPayload = xmpXml.toByteArray(StandardCharsets.UTF_8)
        val app1Segment = buildXmpApp1Segment(xmpPayload)
        val strippedJpegBody = stripExistingXmpApp1Segments(jpegBytes)

        val out = ByteArrayOutputStream(2 + app1Segment.size + strippedJpegBody.size + mp4Bytes.size)
        // JPEG SOI marker
        out.write(0xFF)
        out.write(0xD8)
        // Fresh MotionPhoto APP1 XMP segment right after SOI
        out.write(app1Segment)
        // Remainder of JPEG (after SOI)
        out.write(strippedJpegBody)
        // Appended MP4 micro-video stream
        out.write(mp4Bytes)
        return out.toByteArray()
    }

    internal fun buildMotionPhotoXmp(mp4SizeBytes: Int): String {
        return """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="FoldMedia-Xiaomi-MotionPhoto">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoVersion="1"
                GCamera:MotionPhotoPresentationTimestampUs="0"
                GCamera:MicroVideo="1"
                GCamera:MicroVideoVersion="1"
                GCamera:MicroVideoOffset="$mp4SizeBytes"
                GCamera:MicroVideoPresentationTimestampUs="0">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item
                    Item:Mime="image/jpeg"
                    Item:Semantic="Primary"
                    Item:Length="0"
                    Item:Padding="0"/>
                 </rdf:li>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item
                    Item:Mime="video/mp4"
                    Item:Semantic="MotionPhoto"
                    Item:Length="$mp4SizeBytes"
                    Item:Padding="0"/>
                 </rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }

    private fun buildXmpApp1Segment(xmpUtf8Bytes: ByteArray): ByteArray {
        val totalContentLength = 2 + XMP_NAMESPACE_HEADER.size + xmpUtf8Bytes.size
        require(totalContentLength <= 0xFFFF) { "XMP 数据过大，超出 JPEG APP1 段长度限制" }

        val out = ByteArrayOutputStream(2 + totalContentLength)
        out.write(0xFF)
        out.write(0xE1)
        out.write((totalContentLength ushr 8) and 0xFF)
        out.write(totalContentLength and 0xFF)
        out.write(XMP_NAMESPACE_HEADER)
        out.write(xmpUtf8Bytes)
        return out.toByteArray()
    }

    /**
     * Walks JPEG marker segments after SOI (0xFFD8) and removes any pre-existing XMP APP1 segments
     * while preserving EXIF, ICC, DQT, SOF, DHT, and compressed SOS scan data intact.
     */
    private fun stripExistingXmpApp1Segments(jpegBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(jpegBytes.size)
        var pos = 2 // Skip 0xFF 0xD8
        while (pos < jpegBytes.size) {
            val b0 = jpegBytes[pos].toInt() and 0xFF
            if (b0 != 0xFF) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            if (pos + 1 >= jpegBytes.size) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            // SOS (0xDA) or EOI (0xD9) -> copy everything from here to end
            if (marker == 0xDA || marker == 0xD9) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            // Standalone markers without length (RST0..RST7, TEM, 0x00 padding)
            if (marker == 0x00 || marker in 0xD0..0xD7 || marker == 0x01) {
                out.write(jpegBytes, pos, 2)
                pos += 2
                continue
            }
            if (pos + 3 >= jpegBytes.size) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            val segmentLength = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or
                (jpegBytes[pos + 3].toInt() and 0xFF)
            val totalSegmentSize = 2 + segmentLength
            if (segmentLength < 2 || pos + totalSegmentSize > jpegBytes.size) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }

            val isXmpApp1 = marker == 0xE1 &&
                segmentLength >= 2 + XMP_NAMESPACE_HEADER.size &&
                matchesBytes(jpegBytes, pos + 4, XMP_NAMESPACE_HEADER)

            if (!isXmpApp1) {
                out.write(jpegBytes, pos, totalSegmentSize)
            }
            pos += totalSegmentSize
        }
        return out.toByteArray()
    }

    private fun matchesBytes(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (offset + target.size > source.size) return false
        for (i in target.indices) {
            if (source[offset + i] != target[i]) return false
        }
        return true
    }

    /** Inspection helper for verifying built Motion Photo containers in tests and diagnostics. */
    data class MotionPhotoMetadata(
        val isMotionPhoto: Boolean,
        val videoLengthBytes: Int,
        val trailingSliceStartsWithMp4Ftyp: Boolean
    )

    fun inspectMotionPhoto(bytes: ByteArray): MotionPhotoMetadata {
        if (!isJpeg(bytes)) return MotionPhotoMetadata(false, 0, false)
        val headerProbeSize = minOf(bytes.size, 64 * 1024)
        val headerText = String(bytes, 0, headerProbeSize, StandardCharsets.UTF_8)
        val isMotion = headerText.contains("""GCamera:MotionPhoto="1"""") &&
            headerText.contains("""Item:Semantic="MotionPhoto"""")
        val lengthMatch = Regex("""Item:Semantic="MotionPhoto"\s+Item:Length="(\d+)"""").find(headerText)
        val videoLength = lengthMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val mp4Start = bytes.size - videoLength
        val hasFtyp = videoLength > 8 && mp4Start >= 0 &&
            mp4Start + 8 <= bytes.size &&
            bytes[mp4Start + 4] == 'f'.code.toByte() &&
            bytes[mp4Start + 5] == 't'.code.toByte() &&
            bytes[mp4Start + 6] == 'y'.code.toByte() &&
            bytes[mp4Start + 7] == 'p'.code.toByte()
        return MotionPhotoMetadata(isMotion, videoLength, hasFtyp)
    }
}
