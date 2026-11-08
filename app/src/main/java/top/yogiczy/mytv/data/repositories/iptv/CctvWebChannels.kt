package top.yogiczy.mytv.data.repositories.iptv

import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList

/**
 * 内置的央视网页频道。
 *
 * 背景：央视网 `tv.cctv.com/live/<slug>/` 是官方页面，走官方 CDN，IPv4 可达、不需要订阅源、
 * 也不需要 IPv6 —— 对"找不到稳定 IPTV 源"的网络环境是唯一的零维护兜底方案。
 *
 * 设计原则：**只补位，不覆盖**。
 * - 订阅源里已有的同名频道，不新增条目，只把网页地址追加到最后一条线路；
 *   这样原生线路全挂时，`LeanbackMainContentState` 的多线路切换会自动落到网页播放。
 * - 订阅源里没有的频道，收进"央视·网页频道"分组，保证没配源也能看。
 */
object CctvWebChannels {

    /** 网页频道的地址前缀，播放器据此切换渲染方式 */
    const val WEB_SCHEME = "web://"

    /**
     * @param key     归一化后的匹配键，同时作为 EPG 的 channelName
     * @param name    显示名
     * @param slug    央视网路径，如 cctv1 / cctvjilu
     * @param aliases 额外匹配键（CCTV-9 在央视网的路径是 cctvjilu，CCTV-14 是 cctvchild）
     */
    data class Def(
        val key: String,
        val name: String,
        val slug: String,
        val aliases: List<String> = emptyList(),
    ) {
        val webUrl: String get() = "${WEB_SCHEME}https://tv.cctv.com/live/$slug/"

        fun toIptv(): Iptv = Iptv(
            name = name,
            channelName = key,
            urlList = listOf(webUrl),
        )
    }

    val DEFS: List<Def> = listOf(
        Def("cctv1", "CCTV-1 综合", "cctv1"),
        Def("cctv2", "CCTV-2 财经", "cctv2"),
        Def("cctv3", "CCTV-3 综艺", "cctv3"),
        Def("cctv4", "CCTV-4 中文国际", "cctv4"),
        Def("cctv5", "CCTV-5 体育", "cctv5"),
        Def("cctv5plus", "CCTV-5+ 体育赛事", "cctv5plus"),
        Def("cctv6", "CCTV-6 电影", "cctv6"),
        Def("cctv7", "CCTV-7 国防军事", "cctv7"),
        Def("cctv8", "CCTV-8 电视剧", "cctv8"),
        Def("cctvjilu", "CCTV-9 纪录", "cctvjilu", aliases = listOf("cctv9")),
        Def("cctv10", "CCTV-10 科教", "cctv10"),
        Def("cctv11", "CCTV-11 戏曲", "cctv11"),
        Def("cctv12", "CCTV-12 社会与法", "cctv12"),
        Def("cctv13", "CCTV-13 新闻", "cctv13"),
        Def("cctvchild", "CCTV-14 少儿", "cctvchild", aliases = listOf("cctv14")),
        Def("cctv15", "CCTV-15 音乐", "cctv15"),
        Def("cctv16", "CCTV-16 奥林匹克", "cctv16"),
        Def("cctv17", "CCTV-17 农业农村", "cctv17"),
    )

    private const val WEB_GROUP_NAME = "央视·网页频道"

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

        return DEFS.firstOrNull { def ->
            val keys = listOf(def.key) + def.aliases
            candidates.any { name -> keys.any { key -> matches(name, key) } }
        }
    }

    /**
     * 把内置网页频道合并进订阅源列表：
     * 1. 命中的频道追加一条 `web://` 线路（放在最后，作为兜底）；
     * 2. 未命中的频道归入开头的"央视·网页频道"分组。
     */
    fun merge(source: IptvGroupList): IptvGroupList {
        val matchedKeys = mutableSetOf<String>()

        val mergedGroups = source.map { group ->
            IptvGroup(
                name = group.name,
                iptvList = IptvList(group.iptvList.map { iptv ->
                    val def = matchDef(iptv) ?: return@map iptv

                    matchedKeys += def.key
                    if (iptv.urlList.any { it.startsWith(WEB_SCHEME) }) {
                        iptv
                    } else {
                        iptv.copy(urlList = iptv.urlList + def.webUrl)
                    }
                })
            )
        }.filter { it.iptvList.isNotEmpty() }

        val missing = DEFS.filter { it.key !in matchedKeys }
        if (missing.isEmpty()) return IptvGroupList(mergedGroups)

        val webGroup = IptvGroup(
            name = WEB_GROUP_NAME,
            iptvList = IptvList(missing.map { it.toIptv() }),
        )

        return IptvGroupList(listOf(webGroup) + mergedGroups)
    }

    /** 订阅源完全不可用时的兜底列表 */
    fun fallback(): IptvGroupList = IptvGroupList(
        listOf(
            IptvGroup(
                name = WEB_GROUP_NAME,
                iptvList = IptvList(DEFS.map { it.toIptv() }),
            )
        )
    )
}
