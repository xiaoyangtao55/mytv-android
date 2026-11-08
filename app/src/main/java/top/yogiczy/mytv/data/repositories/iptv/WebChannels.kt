package top.yogiczy.mytv.data.repositories.iptv

import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList

/**
 * 内置的网页频道：央视网 + 央视频。
 *
 * 背景：`tv.cctv.com/live/<slug>/` 与 `yangshipin.cn/tv/home?pid=<pid>` 都是官方页面，
 * 走官方 CDN、IPv4 可达、不需要订阅源、也不依赖 IPv6 —— 对"找不到稳定 IPTV 源"的
 * 网络环境是唯一零维护的兜底方案。这也顺带覆盖了绝大多数卫视：央视频是官方直播平台，
 * 各省卫视都有独立的 pid。
 *
 * 播放方式不是"让网页自己播"，而是**网页只负责找流**：
 * `LeanbackWebVideoScreen` 通过 `shouldInterceptRequest` 截下真正的 m3u8，
 * 交给 ExoPlayer 硬解。原因是盒子的系统 WebView 常常缺 MSE 或硬解能力，
 * 页面能渲染、节目单能出来，但 `<video>` 就是一片黑。
 *
 * 设计原则：**只补线路，不加频道**。
 * - 订阅源里已有的同名频道，不新增条目，只把网页地址追加到最后一条线路；
 *   这样原生线路全挂时，`LeanbackMainContentState` 的多线路切换会自动落到网页。
 * - 订阅源里没有的频道一律不补，列表内容始终等于用户自己的源。
 * - 只有订阅源完全不可用（没配 / 拉取失败 / 解析为空）时才走 [fallback]。
 *
 * 该行为受设置项「央视网页兜底」控制，关闭后 [merge] 不会被调用。
 */
object WebChannels {

    /** 网页频道的地址前缀，播放器据此切换渲染方式 */
    const val WEB_SCHEME = "web://"

    /**
     * @param key     匹配键，同时作为 EPG 的 channelName
     * @param name    显示名
     * @param pageUrl 官方直播页地址
     * @param aliases 额外匹配键（CCTV-9 在央视网的路径是 cctvjilu，CCTV-14 是 cctvchild）
     */
    data class Def(
        val key: String,
        val name: String,
        val pageUrl: String,
        val aliases: List<String> = emptyList(),
    ) {
        val webUrl: String get() = "$WEB_SCHEME$pageUrl"

        fun toIptv(): Iptv = Iptv(
            name = name,
            channelName = key,
            urlList = listOf(webUrl),
        )
    }

    private fun cctv(
        slug: String,
        key: String,
        name: String,
        aliases: List<String> = emptyList(),
    ) = Def(key, name, "https://tv.cctv.com/live/$slug/", aliases)

    private fun ysp(pid: String, name: String) =
        Def(key = name, name = name, pageUrl = "https://www.yangshipin.cn/tv/home?pid=$pid")

    val CCTV_DEFS: List<Def> = listOf(
        cctv("cctv1", "cctv1", "CCTV-1 综合"),
        cctv("cctv2", "cctv2", "CCTV-2 财经"),
        cctv("cctv3", "cctv3", "CCTV-3 综艺"),
        cctv("cctv4", "cctv4", "CCTV-4 中文国际"),
        // 5+ 必须排在 5 前面：matchDef 按最长命中取，但顺序上先保证语义直观
        cctv("cctv5plus", "cctv5plus", "CCTV-5+ 体育赛事"),
        cctv("cctv5", "cctv5", "CCTV-5 体育"),
        cctv("cctv6", "cctv6", "CCTV-6 电影"),
        cctv("cctv7", "cctv7", "CCTV-7 国防军事"),
        cctv("cctv8", "cctv8", "CCTV-8 电视剧"),
        cctv("cctvjilu", "cctvjilu", "CCTV-9 纪录", aliases = listOf("cctv9")),
        cctv("cctv10", "cctv10", "CCTV-10 科教"),
        cctv("cctv11", "cctv11", "CCTV-11 戏曲"),
        cctv("cctv12", "cctv12", "CCTV-12 社会与法"),
        cctv("cctv13", "cctv13", "CCTV-13 新闻"),
        cctv("cctvchild", "cctvchild", "CCTV-14 少儿", aliases = listOf("cctv14")),
        cctv("cctv15", "cctv15", "CCTV-15 音乐"),
        cctv("cctv16", "cctv16", "CCTV-16 奥林匹克"),
        cctv("cctv17", "cctv17", "CCTV-17 农业农村"),
    )

    /** 央视频的卫视 pid */
    val SATELLITE_DEFS: List<Def> = listOf(
        ysp("600002532", "安徽卫视"),
        ysp("600002309", "北京卫视"),
        ysp("600002484", "东南卫视"),
        ysp("600002483", "东方卫视"),
        ysp("600002485", "广东卫视"),
        ysp("600002509", "广西卫视"),
        ysp("600002490", "贵州卫视"),
        ysp("600002506", "海南卫视"),
        ysp("600002493", "河北卫视"),
        ysp("600002525", "河南卫视"),
        ysp("600002498", "黑龙江卫视"),
        ysp("600002508", "湖北卫视"),
        ysp("600002475", "湖南卫视"),
        ysp("600002521", "江苏卫视"),
        ysp("600002503", "江西卫视"),
        ysp("600002505", "辽宁卫视"),
        ysp("600002531", "重庆卫视"),
        ysp("600002513", "山东卫视"),
        ysp("600002481", "深圳卫视"),
        ysp("600002516", "四川卫视"),
        ysp("600002520", "浙江卫视"),
    )

    val DEFS: List<Def> = CCTV_DEFS + SATELLITE_DEFS

    private const val CCTV_GROUP_NAME = "央视·网页频道"
    private const val SATELLITE_GROUP_NAME = "卫视·网页频道"

    /** 归一化：小写、`+` 变 plus、去掉一切非字母数字（连字符/空格/中括号） */
    private fun normalize(raw: String): String =
        raw.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    private fun matches(name: String, key: String): Boolean {
        if (!name.startsWith(key)) return false
        // CCTV-1 不能吞掉 CCTV-11
        return name.length == key.length || !name[key.length].isDigit()
    }

    private fun matchDef(iptv: Iptv): Def? {
        val candidates = listOf(iptv.channelName, iptv.name)
            .filter { it.isNotBlank() }
            .map(::normalize)
        if (candidates.isEmpty()) return null

        // 取最长命中：Def 里存在 cctv5 / cctv5plus 这类互为前缀的键
        return DEFS.filter { def ->
            val keys = listOf(def.key) + def.aliases
            candidates.any { name -> keys.any { key -> matches(name, key) } }
        }.maxByOrNull { it.key.length }
    }

    /**
     * 给订阅源里命中的频道追加一条 `web://` 兜底线路。
     *
     * 只动 `urlList`，不新增也不删除任何频道——用户配了什么就显示什么。
     */
    fun merge(source: IptvGroupList): IptvGroupList = IptvGroupList(
        source.map { group ->
            IptvGroup(
                name = group.name,
                iptvList = IptvList(group.iptvList.map { iptv ->
                    val def = matchDef(iptv) ?: return@map iptv

                    if (iptv.urlList.any { it.startsWith(WEB_SCHEME) }) {
                        iptv
                    } else {
                        iptv.copy(urlList = iptv.urlList + def.webUrl)
                    }
                })
            )
        }.filter { it.iptvList.isNotEmpty() }
    )

    /** 订阅源完全不可用时的兜底列表 */
    fun fallback(): IptvGroupList = IptvGroupList(
        listOf(
            IptvGroup(
                name = CCTV_GROUP_NAME,
                iptvList = IptvList(CCTV_DEFS.map { it.toIptv() }),
            ),
            IptvGroup(
                name = SATELLITE_GROUP_NAME,
                iptvList = IptvList(SATELLITE_DEFS.map { it.toIptv() }),
            ),
        )
    )
}
