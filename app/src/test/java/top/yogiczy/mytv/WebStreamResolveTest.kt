package top.yogiczy.mytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.ui.screens.leanback.video.isStreamPlaylist
import top.yogiczy.mytv.ui.screens.leanback.video.refererOf

/**
 * 网页线路"找流"的两个纯函数。
 *
 * 这两个判断直接决定能不能从电视台页面里挑出可播地址：
 * 挑错了会交给 ExoPlayer 一个子播放列表或分片，播不出来。
 */
class WebStreamResolveTest {

    @Test
    fun `央视主播放列表应被识别`() {
        assertTrue(
            isStreamPlaylist("https://ldncctvwbcdbyte.volcfcdn.com/ldncctvwbcd/cdrmldcctv1_1/index.m3u8?b=200-2100")
        )
    }

    @Test
    fun `央视码率子列表应被排除`() {
        // 子列表是主列表的变体，交给 ExoPlayer 会丢掉自适应，而且它带 BR= 参数
        assertFalse(
            isStreamPlaylist("https://ldncctvwbcdbyte.volcfcdn.com/ldncctvwbcd/cdrmldcctv1_1/index.m3u8?BR=td")
        )
    }

    @Test
    fun `ts 分片与 blob 地址不算播放列表`() {
        assertFalse(isStreamPlaylist("https://x.com/a/seg001.ts"))
        assertFalse(isStreamPlaylist("blob:https://tv.cctv.com/68c543f2-e90f-4da2"))
        assertFalse(isStreamPlaylist("about:blank"))
    }

    @Test
    fun `荔枝网的签名地址应被识别`() {
        assertTrue(
            isStreamPlaylist("https://litchi-play-encrypted-site.jstv.com/applive/jswspro.m3u8?txSecret=abc&txTime=6af08e8f")
        )
    }

    @Test
    fun `Referer 取页面 origin`() {
        assertEquals("https://live.jstv.com/", refererOf("https://live.jstv.com/?channelid=535"))
        assertEquals("https://tv.cctv.com/", refererOf("https://tv.cctv.com/live/cctv1/"))
        assertEquals("http://127.0.0.1:8099/", refererOf("http://127.0.0.1:8099/page.html"))
    }

    @Test
    fun `非 http 页面取不到 Referer`() {
        assertNull(refererOf("about:blank"))
        assertNull(refererOf(""))
    }
}
