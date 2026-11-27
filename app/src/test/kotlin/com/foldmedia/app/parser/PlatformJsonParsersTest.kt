package com.foldmedia.app.parser

import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun parsesDouyinBitRateLivePhotoPreferringH264() {
        val html = """
            <script>window._ROUTER_DATA = {"loaderData":{"slides_(id)/page":{"awemeDetail":{"aweme_id":"75112233","desc":"公园春日实况","author":{"nickname":"拍客"},"images":[{"width":1440,"height":1920,"url_list":["https://p11.douyinpic.com/spring.jpeg"],"video":{"duration":2100,"bit_rate":[{"is_h265":1,"bit_rate":3000000,"play_addr":{"url_list":["https://www.douyin.com/aweme/v1/playwm/?video_id=h265"]}},{"is_h265":0,"bit_rate":2000000,"play_addr":{"url_list":["https://www.douyin.com/aweme/v1/playwm/?video_id=h264"]}}]}}]}}}};</script>
        """.trimIndent()

        val post = PlatformJsonParsers.parseDouyinRouterData(html, "https://v.douyin.com/s", "https://www.iesdouyin.com/share/slides/75112233/")
        assertEquals(1, post.items.size)
        assertEquals(MediaKind.LIVE_PHOTO, post.items[0].kind)
        assertEquals("https://www.douyin.com/aweme/v1/play/?video_id=h264", post.items[0].videoUrl)
        assertEquals(listOf("https://www.douyin.com/aweme/v1/play/?video_id=h265"), post.items[0].backupVideoUrls)
    }

    @Test
    fun parsesDouyinPcPaceFlightCamelCaseLivePhoto() {
        val paceJson = """
            {"awemeId":"7641537240552954725","aweme":{"statusCode":0,"detail":{"desc":"live图 /50张实况图","authorInfo":{"nickname":"摄影号"},"images":[{"width":1080,"height":1440,"urlList":["https://p3-pc-sign.douyinpic.com/a.webp","https://p3-pc-sign.douyinpic.com/a.jpeg"],"downloadUrlList":["https://p3-pc-sign.douyinpic.com/a~tplv-dy-water-v2.webp"],"video":{"duration":2867,"playAddr":[{"src":"https://v26-web.douyinvod.com/live_clip.mp4?a=6383"}]}}]}}}
        """.trimIndent()

        val post = PlatformJsonParsers.parseDouyinRouterData(
            rawHtmlOrJson = paceJson,
            originalInput = "https://v.douyin.com/9MVxV9pQ9iU/",
            resolvedUrl = "https://www.iesdouyin.com/share/slides/7641537240552954725/"
        )
        assertEquals("7641537240552954725", post.id)
        assertEquals("摄影号", post.authorName)
        assertEquals(1, post.items.size)
        assertEquals(MediaKind.LIVE_PHOTO, post.items[0].kind)
        assertEquals("https://p3-pc-sign.douyinpic.com/a.jpeg", post.items[0].imageUrl)
        assertEquals("https://v26-web.douyinvod.com/live_clip.mp4?a=6383", post.items[0].videoUrl)
        assertEquals(2867L, post.items[0].durationMs)
    }

    @Test
    fun parsesTwitterHlsAndVxTwitterMirror() {
        val fxHlsOnly = """
            {
              "code": 200,
              "tweet": {
                "id": "1900999888",
                "text": "HLS video only tweet",
                "author": { "name": "Demo", "screen_name": "demo" },
                "media": {
                  "videos": [
                    {
                      "type": "video",
                      "url": "https://video.twimg.com/ext_tw_video/9/pu/pl/master.m3u8",
                      "thumbnail_url": "https://pbs.twimg.com/thumb.jpg",
                      "width": 1280,
                      "height": 720,
                      "variants": [
                        { "content_type": "application/x-mpegURL", "url": "https://video.twimg.com/ext_tw_video/9/pu/pl/master.m3u8" }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val fxPost = PlatformJsonParsers.parseFxTwitterJson(fxHlsOnly, "https://x.com/demo/status/1900999888", "https://x.com/demo/status/1900999888")
        assertTrue(fxPost.items[0].requiresYtDlpDownload)
        assertEquals("hls_best_mp4", fxPost.items[0].selectedQualityId)

        val vxJson = """
            {
              "tweetID": "1900777666",
              "text": "VxTwitter sensitive sample",
              "user_name": "Creator",
              "user_screen_name": "creator",
              "media_extended": [
                { "type": "image", "url": "https://pbs.twimg.com/media/VxImg1.jpg", "size": { "width": 1200, "height": 1600 } },
                { "type": "video", "url": "https://video.twimg.com/ext_tw_video/2/vid/avc1/720x1280/v.mp4", "thumbnail_url": "https://pbs.twimg.com/vthumb.jpg", "size": { "width": 720, "height": 1280 }, "duration_millis": 8000 }
              ]
            }
        """.trimIndent()
        val vxPost = PlatformJsonParsers.parseVxTwitterJson(vxJson, "https://x.com/creator/status/1900777666", "https://x.com/creator/status/1900777666")
        assertEquals(2, vxPost.items.size)
        assertEquals("https://pbs.twimg.com/media/VxImg1.jpg?name=orig", vxPost.items[0].imageUrl)
        assertFalse(vxPost.items[1].requiresYtDlpDownload)
    }

    @Test
    fun parsesWeChatArticleAndStickerHtml() {
        val oaHtml = """
            <html>
              <head>
                <meta property="og:title" content="小米 18 Fold 实拍样张" />
                <meta property="og:image" content="http://mmbiz.qpic.cn/mmbiz_jpg/cover1/640?wx_fmt=jpeg&amp;tp=webp" />
                <meta name="author" content="FoldMedia 观察" />
              </head>
              <body>
                <div id="js_content" style="visibility: hidden; opacity: 0;">
                  <p>今天测试一组折叠屏样张。</p>
                  <img data-src="http://mmbiz.qpic.cn/mmbiz_png/pic1/640?wx_fmt=png&tp=webp" />
                  <img data-src="http://mmbiz.qpic.cn/mmbiz_gif/gif1/640?wx_fmt=gif&tp=webp" />
                  <img src="https://res.wx.qq.com/wx_fed/assets/icon.png" />
                </div>
              </body>
            </html>
        """.trimIndent()

        val oaPost = PlatformJsonParsers.parseWeChatHtml(oaHtml, "https://mp.weixin.qq.com/s/TestArticle", "https://mp.weixin.qq.com/s/TestArticle")
        assertEquals(PlatformType.WECHAT, oaPost.platform)
        assertEquals("小米 18 Fold 实拍样张", oaPost.title)
        assertEquals("FoldMedia 观察", oaPost.authorName)
        assertEquals(3, oaPost.items.size)
        assertEquals("文章封面", oaPost.items[0].title)
        assertEquals("https://mmbiz.qpic.cn/mmbiz_png/pic1/0?wx_fmt=png", oaPost.items[1].imageUrl)
        assertEquals("https://mmbiz.qpic.cn/mmbiz_gif/gif1/0?wx_fmt=gif", oaPost.items[2].imageUrl)
        assertTrue(oaPost.caption.contains("今天测试一组折叠屏样张"))
        assertFalse(oaPost.caption.contains("js_content"))
        assertFalse(oaPost.caption.contains("visibility"))
    }

    @Test
    fun parsesFxTwitterV2StatusAndFormatsWithHlsMergeOption() {
        val fxV2 = """
            {
              "code": 200,
              "status": {
                "id": "719944021058060289",
                "text": "Are you sure you made the right choice?",
                "author": { "name": "Captain America", "screen_name": "CaptainAmerica" },
                "media": {
                  "all": [
                    {
                      "id": "717462543795523584",
                      "type": "video",
                      "url": "https://video.twimg.com/ext_tw_video/717/jk.mp4",
                      "thumbnail_url": "https://pbs.twimg.com/ext_tw_video_thumb/717/thumb.jpg",
                      "width": 1280,
                      "height": 720,
                      "formats": [
                        { "url": "https://video.twimg.com/ext_tw_video/717/pu/pl/master.m3u8", "container": "m3u8" },
                        { "url": "https://video.twimg.com/ext_tw_video/717/low.mp4", "bitrate": 320000, "container": "mp4" },
                        { "url": "https://video.twimg.com/ext_tw_video/717/high.mp4", "bitrate": 2176000, "container": "mp4" }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val post = PlatformJsonParsers.parseFxTwitterJson(fxV2, "https://x.com/CaptainAmerica/status/719944021058060289", "https://x.com/CaptainAmerica/status/719944021058060289")
        assertEquals(PlatformType.X_TWITTER, post.platform)
        assertEquals(1, post.items.size)
        val item = post.items[0]
        assertEquals("https://video.twimg.com/ext_tw_video/717/high.mp4", item.videoUrl)
        assertFalse(item.requiresYtDlpDownload)
        assertEquals(listOf("direct_mp4", "hls_merge_mp4", "audio_m4a"), item.qualityOptions.map { it.id })
    }

    @Test
    fun parsesDouyinWebDetailLivePhotoAndSkipsWatermarkedUrls() {
        val rawWebDetail = """
            {
              "status_code": 0,
              "aweme_detail": {
                "aweme_id": "7641537240552954725",
                "desc": "50张实况图分享",
                "author": { "nickname": "陶西" },
                "images": [
                  {
                    "clip_type": 5,
                    "live_photo_type": 1,
                    "width": 1080,
                    "height": 1440,
                    "url_list": [
                      "https://p11-sign.douyinpic.com/clean.webp"
                    ],
                    "download_url_list": [
                      "https://p26-sign.douyinpic.com/water~tplv-dy-water-v2:1080:1440.jpeg"
                    ],
                    "video": {
                      "duration": 2200,
                      "play_addr": {
                        "url_list": ["https://v26.douyinvod.com/aweme/v1/playwm/?video_id=live99"]
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val post = PlatformJsonParsers.parseDouyinRouterData(
            rawWebDetail,
            "https://v.douyin.com/9MVxV9pQ9iU/",
            "https://www.iesdouyin.com/share/slides/7641537240552954725/?is_slides=1"
        )
        assertEquals(1, post.items.size)
        assertEquals(MediaKind.LIVE_PHOTO, post.items[0].kind)
        assertEquals("https://p11-sign.douyinpic.com/clean.webp", post.items[0].imageUrl)
        assertEquals("https://v26.douyinvod.com/aweme/v1/play/?video_id=live99", post.items[0].videoUrl)
    }

    @Test
    fun parsesXiaohongshuMobileNoteDataLivePhoto() {
        val mobileHtml = """
            <html><body><script>window.__INITIAL_STATE__={"global":{"supportWebp":true},"noteData":{"data":{"noteData":{"noteId":"6b01911b000000002a038edc","type":"normal","title":"大连海边雪景","desc":"蓝调时刻","user":{"nickname":"旅拍者"},"imageList":[{"width":1920,"height":2560,"url":"http://sns-webpic-qc.xhscdn.com/preview.jpg","infoList":[{"url":"http://sns-webpic-qc.xhscdn.com/h5_dtl.jpg","imageScene":"H5_DTL"}],"livePhoto":true,"stream":{"h264":[{"masterUrl":"http://sns-video-v6.xhscdn.com/live.mp4","backupUrls":["http://sns-bak-v1.xhscdn.com/live_bak.mp4"]}]}}]}}}}</script></body></html>
        """.trimIndent()

        val post = PlatformJsonParsers.parseXiaohongshuInitialState(
            rawHtmlOrJson = mobileHtml,
            originalInput = "https://xhslink.cn/o/5FmFyZt7HDO",
            resolvedUrl = "https://www.xiaohongshu.com/discovery/item/6b01911b000000002a038edc"
        )
        assertEquals(PlatformType.XIAOHONGSHU, post.platform)
        assertEquals("6b01911b000000002a038edc", post.id)
        assertEquals("旅拍者", post.authorName)
        assertEquals(1, post.items.size)
        assertEquals(MediaKind.LIVE_PHOTO, post.items[0].kind)
        assertEquals("https://sns-webpic-qc.xhscdn.com/h5_dtl.jpg", post.items[0].imageUrl)
        assertEquals("https://sns-video-v6.xhscdn.com/live.mp4", post.items[0].videoUrl)
    }
}
