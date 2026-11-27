package com.foldmedia.app.parser

import com.foldmedia.app.model.PlatformType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun detectsWeChatLinksAndNormalizesImages() {
        val oaUrl = "https://mp.weixin.qq.com/s/AbCdEf_123"
        val stickerUrl = "https://sticker.weixin.qq.com/cgi-bin/mmemoticon-bin/emoticonview?t=wap/index&product_id=12345"
        assertEquals(PlatformType.WECHAT, LinkSanitizer.detectPlatform(oaUrl))
        assertEquals(PlatformType.WECHAT, LinkSanitizer.detectPlatform(stickerUrl))
        assertEquals("AbCdEf_123", LinkSanitizer.extractWeChatArticleId(oaUrl))

        assertEquals(
            "https://mmbiz.qpic.cn/mmbiz_png/abc/0?wx_fmt=png",
            LinkSanitizer.normalizeWeChatImageUrl("http://mmbiz.qpic.cn/mmbiz_png/abc/640?wx_fmt=png&amp;tp=webp")
        )
        assertEquals(
            "https://mmbiz.qpic.cn/mmbiz_gif/xyz/0?wx_fmt=gif",
            LinkSanitizer.normalizeWeChatImageUrl("http://mmbiz.qpic.cn/mmbiz_gif/xyz/640?wx_fmt=gif&tp=webp")
        )
    }

    @Test
    fun detectsShortLinksHlsStreamsDouyinKindsAndTwitterToken() {
        assertTrue(LinkSanitizer.isShortRedirectUrl("https://v.douyin.com/iY8kL9x/"))
        assertTrue(LinkSanitizer.isShortRedirectUrl("https://t.co/abc123"))
        assertFalse(LinkSanitizer.isShortRedirectUrl("https://x.com/user/status/1899999999999999"))
        assertFalse(LinkSanitizer.isShortRedirectUrl("https://mp.weixin.qq.com/s/AbCdEf_123"))

        assertTrue(LinkSanitizer.isHlsStreamUrl("https://video.twimg.com/amplify_video/1/pl/a.m3u8?tag=14"))
        assertFalse(LinkSanitizer.isHlsStreamUrl("https://video.twimg.com/ext_tw_video/1/vid/720/a.mp4"))

        assertEquals("slides", LinkSanitizer.detectDouyinShareKind("https://www.iesdouyin.com/share/slides/74001122/"))
        assertEquals("note", LinkSanitizer.detectDouyinShareKind("https://www.douyin.com/note/74001122"))
        assertEquals("video", LinkSanitizer.detectDouyinShareKind("https://www.iesdouyin.com/share/video/74001122/"))

        val token = LinkSanitizer.computeTwitterSyndicationToken("1900123456789012345")
        assertTrue(token.isNotBlank())
        assertTrue(token.all { it.isLetterOrDigit() })
    }
}
