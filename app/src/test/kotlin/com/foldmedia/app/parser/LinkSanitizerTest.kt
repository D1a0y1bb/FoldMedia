package com.foldmedia.app.parser

import com.foldmedia.app.model.PlatformType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkSanitizerTest {

    @Test
    fun extractsCleanDouyinUrlFromChineseShareCopy() {
        val raw = "8.74 复制打开抖音，看看【超美日落实况】 https://v.douyin.com/iY8kL9x/，打开Dou音搜索！"
        val url = LinkSanitizer.extractCleanUrl(raw)
        assertEquals("https://v.douyin.com/iY8kL9x/", url)
        assertEquals(PlatformType.DOUYIN, LinkSanitizer.detectPlatform(url!!))
    }

    @Test
    fun extractsCleanXiaohongshuUrlFromShareText() {
        val raw = "24 【夏日实况照片分享】 http://xhslink.com/a/AbCdEf123】复制本条信息打开小红书"
        val url = LinkSanitizer.extractCleanUrl(raw)
        assertEquals("http://xhslink.com/a/AbCdEf123", url)
        assertEquals(PlatformType.XIAOHONGSHU, LinkSanitizer.detectPlatform(url!!))
    }

    @Test
    fun extractsIdsForAllSupportedPlatforms() {
        assertEquals(
            "1899999999999999",
            LinkSanitizer.extractTweetId("https://x.com/xiaomi/status/1899999999999999?s=20")
        )
        assertEquals(
            "7398765432101234567",
            LinkSanitizer.extractDouyinItemId("https://www.iesdouyin.com/share/video/7398765432101234567/")
        )
        assertEquals(
            "66a1b2c3000000001e012345",
            LinkSanitizer.extractXhsNoteId("https://www.xiaohongshu.com/explore/66a1b2c3000000001e012345?xsec_token=abc")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            LinkSanitizer.extractYouTubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            LinkSanitizer.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=test")
        )
    }

    @Test
    fun upgradesTwitterMediaToOrigAndStripsDouyinWatermark() {
        assertEquals(
            "https://pbs.twimg.com/media/AbCdEfG?format=jpg&name=orig",
            LinkSanitizer.upgradeTwitterImageToOriginal("http://pbs.twimg.com/media/AbCdEfG?format=jpg&name=small")
        )
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=123",
            LinkSanitizer.stripDouyinWatermark("http://aweme.snssdk.com/aweme/v1/playwm/?video_id=123")
        )
    }

    @Test
    fun returnsNullWhenNoUrlPresent() {
        assertNull(LinkSanitizer.extractCleanUrl("纯文本不包含任何链接"))
    }
}
