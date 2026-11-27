package com.foldmedia.app.parser

import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformJsonParsersTest {

    @Test
    fun parsesFxTwitterMixedPhotosAndVideos() {
        val sample = """
            {
              "code": 200,
              "message": "OK",
              "tweet": {
                "id": "1900123456",
                "text": "Xiaomi 18 Fold camera samples",
                "author": { "name": "Lei Jun", "screen_name": "leijun" },
                "media": {
                  "photos": [
                    { "type": "photo", "url": "https://pbs.twimg.com/media/Img1?format=jpg&name=large", "width": 2048, "height": 1536 },
                    { "type": "photo", "url": "https://pbs.twimg.com/media/Img2?format=png", "width": 1080, "height": 1350 }
                  ],
                  "videos": [
                    {
                      "type": "video",
                      "url": "https://video.twimg.com/ext_tw_video/1/vid/720/a.mp4",
                      "thumbnail_url": "https://pbs.twimg.com/ext_tw_video_thumb/1.jpg",
                      "width": 1280,
                      "height": 720,
                      "duration": 12.5,
                      "variants": [
                        { "bitrate": 832000, "content_type": "video/mp4", "url": "https://video.twimg.com/low.mp4" },
                        { "bitrate": 2176000, "content_type": "video/mp4", "url": "https://video.twimg.com/high.mp4" }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val post = PlatformJsonParsers.parseFxTwitterJson(sample, "https://x.com/leijun/status/1900123456", "https://x.com/leijun/status/1900123456")
        assertEquals(PlatformType.X_TWITTER, post.platform)
        assertEquals("@leijun", post.authorHandle)
        assertEquals(3, post.items.size)
        assertEquals("https://pbs.twimg.com/media/Img1?format=jpg&name=orig", post.items[0].imageUrl)
        assertEquals("https://pbs.twimg.com/media/Img2?format=png&name=orig", post.items[1].imageUrl)
        assertEquals(MediaKind.VIDEO, post.items[2].kind)
        assertEquals("https://video.twimg.com/high.mp4", post.items[2].videoUrl)
    }

    @Test
    fun parsesXiaohongshuHtmlWithUndefinedAndLivePhotos() {
        val html = """
            <html><head></head><body>
            <script>window.__INITIAL_STATE__={"user":{"loggedIn":undefined},"note":{"noteDetailMap":{"66abc123":{"note":{"noteId":"66abc123","type":"normal","title":"秋日银杏实况","desc":"实况拍照测试 #小米","user":{"nickname":"摄影日记"},"imageList":[{"width":1440,"height":1920,"urlDefault":"//sns-img-qc.xhscdn.com/pic1.jpg","livePhoto":true,"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/live1.mp4","backupUrls":["https://sns-video-al.xhscdn.com/live1_bak.mp4"]}]}},{"width":1440,"height":1920,"urlDefault":"http://sns-img-qc.xhscdn.com/pic2.jpg","livePhoto":false}]}}}}};</script>
            </body></html>
        """.trimIndent()

        val post = PlatformJsonParsers.parseXiaohongshuInitialState(html, "http://xhslink.com/a/123", "https://www.xiaohongshu.com/explore/66abc123")
        assertEquals(PlatformType.XIAOHONGSHU, post.platform)
        assertEquals("秋日银杏实况", post.title)
        assertEquals(2, post.items.size)

        val liveItem = post.items[0]
        assertEquals(MediaKind.LIVE_PHOTO, liveItem.kind)
        assertEquals("https://sns-img-qc.xhscdn.com/pic1.jpg", liveItem.imageUrl)
        assertEquals("https://sns-video-bd.xhscdn.com/live1.mp4", liveItem.videoUrl)
        assertEquals(listOf("https://sns-video-al.xhscdn.com/live1_bak.mp4"), liveItem.backupVideoUrls)

        val staticItem = post.items[1]
        assertEquals(MediaKind.IMAGE, staticItem.kind)
        assertEquals("https://sns-img-qc.xhscdn.com/pic2.jpg", staticItem.imageUrl)
    }

    @Test
    fun parsesDouyinRouterDataLivePhotoCarouselIgnoringOuterBgm() {
        val html = """
            <html><body><script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"74001122","desc":"海边实况图集 #旅行","author":{"nickname":"旅拍君"},"images":[{"width":1080,"height":1440,"url_list":["https://p11.douyinpic.com/img1.webp","https://p11.douyinpic.com/img1.jpeg"],"video":{"duration":2400,"play_addr":{"url_list":["https://www.douyin.com/aweme/v1/playwm/?video_id=live1"]}}},{"width":1080,"height":1440,"url_list":["https://p11.douyinpic.com/img2.jpeg"]}],"video":{"play_addr":{"url_list":["https://sf3-cdn.douyinobj.com/bgm.mp3"]}}}]}}}}</script></body></html>
        """.trimIndent()

        val post = PlatformJsonParsers.parseDouyinRouterData(html, "https://v.douyin.com/test", "https://www.iesdouyin.com/share/video/74001122/")
        assertEquals(PlatformType.DOUYIN, post.platform)
        assertEquals(2, post.items.size)
        assertEquals(MediaKind.LIVE_PHOTO, post.items[0].kind)
        assertEquals("https://p11.douyinpic.com/img1.jpeg", post.items[0].imageUrl)
        assertEquals("https://www.douyin.com/aweme/v1/play/?video_id=live1", post.items[0].videoUrl)
        assertEquals(MediaKind.IMAGE, post.items[1].kind)
        assertTrue(post.items[1].videoUrl == null)
    }

    @Test
    fun parsesDouyinRouterDataWatermarkFreeVideo() {
        val html = """
            <script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"749999","desc":"航拍风景","author":{"nickname":"航拍"},"video":{"width":1920,"height":1080,"duration":15000,"cover":{"url_list":["https://p3.douyinpic.com/cover.jpg"]},"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200"]}}}]}}}};</script>
        """.trimIndent()

        val post = PlatformJsonParsers.parseDouyinRouterData(html, "https://v.douyin.com/v", "https://www.iesdouyin.com/share/video/749999/")
        assertEquals(1, post.items.size)
        assertEquals(MediaKind.VIDEO, post.items[0].kind)
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200", post.items[0].videoUrl)
        assertEquals("https://p3.douyinpic.com/cover.jpg", post.items[0].imageUrl)
    }
}
