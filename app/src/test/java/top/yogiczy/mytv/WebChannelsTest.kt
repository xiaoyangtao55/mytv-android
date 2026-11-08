package top.yogiczy.mytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.data.repositories.iptv.WebChannels

/**
 * 内置网页频道的合并语义。
 *
 * 核心约定：**只补线路，不加频道**。用户配了什么就显示什么，
 * 唯一例外是订阅源完全不可用时才整组兜底。
 */
class WebChannelsTest {

    private fun groupOf(vararg iptvs: Iptv) =
        IptvGroupList(listOf(IptvGroup(name = "我的源", iptvList = IptvList(iptvs.toList()))))

    private fun IptvGroupList.all(): List<Iptv> = flatMap { it.iptvList }

    @Test
    fun `兜底列表同时包含央视与卫视两组`() {
        val groups = WebChannels.fallback().toList()

        assertEquals(2, groups.size)
        assertEquals(WebChannels.CCTV_DEFS.size, groups[0].iptvList.size)
        assertEquals(WebChannels.SATELLITE_DEFS.size, groups[1].iptvList.size)
        assertTrue(groups[1].iptvList.any { it.name == "江苏卫视" })
    }

    @Test
    fun `命中的频道追加一条网页线路且不改变频道数`() {
        val source = groupOf(
            Iptv(name = "CCTV-1 综合", channelName = "cctv1", urlList = listOf("http://a/1.m3u8"))
        )

        val merged = WebChannels.merge(source)
        val iptv = merged.all().single()

        assertEquals(2, iptv.urlList.size)
        assertEquals("http://a/1.m3u8", iptv.urlList.first())
        assertTrue(iptv.urlList.last().startsWith(WebChannels.WEB_SCHEME))
        assertTrue(iptv.urlList.last().contains("cctv1"))
    }

    @Test
    fun `源里没有的频道不会被补进来`() {
        val source = groupOf(
            Iptv(name = "某地方台", channelName = "local", urlList = listOf("http://a/1.m3u8"))
        )

        val merged = WebChannels.merge(source)
        val iptv = merged.all().single()

        assertEquals(1, merged.all().size)
        assertEquals(1, iptv.urlList.size)
    }

    @Test
    fun `卫视按名称命中央视频页面`() {
        val source = groupOf(
            Iptv(name = "江苏卫视高清", channelName = "", urlList = listOf("http://a/1.m3u8"))
        )

        val url = WebChannels.merge(source).all().single().urlList.last()

        assertTrue(url.startsWith(WebChannels.WEB_SCHEME))
        assertTrue(url.contains("yangshipin.cn"))
        assertTrue(url.contains("600002521"))
    }

    @Test
    fun `CCTV-5+ 不会被当成 CCTV-5`() {
        val source = groupOf(
            Iptv(name = "CCTV-5+ 体育赛事", channelName = "cctv5plus", urlList = listOf("http://a/1.m3u8"))
        )

        val url = WebChannels.merge(source).all().single().urlList.last()

        assertTrue("应命中 5+，实际是 $url", url.contains("cctv5plus"))
    }

    @Test
    fun `已有网页线路时不重复追加`() {
        val webUrl = WebChannels.CCTV_DEFS.first().webUrl
        val source = groupOf(
            Iptv(name = "CCTV-1 综合", channelName = "cctv1", urlList = listOf("http://a/1.m3u8", webUrl))
        )

        val iptv = WebChannels.merge(source).all().single()

        assertEquals(2, iptv.urlList.size)
    }
}
