package top.yogiczy.mytv.ui.screens.leanback.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvList
import top.yogiczy.mytv.data.repositories.iptv.CctvWebChannels
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.video.LeanbackVideoPlayerState
import top.yogiczy.mytv.ui.screens.leanback.video.rememberLeanbackVideoPlayerState
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Loggable
import kotlin.math.max

@Stable
class LeanbackMainContentState(
    coroutineScope: CoroutineScope,
    private val videoPlayerState: LeanbackVideoPlayerState,
    private val iptvGroupList: IptvGroupList,
) : Loggable() {
    private var _currentIptv by mutableStateOf(Iptv())
    val currentIptv get() = _currentIptv

    private var _currentIptvUrlIdx by mutableIntStateOf(0)
    val currentIptvUrlIdx get() = _currentIptvUrlIdx

    private var _isPanelVisible by mutableStateOf(false)
    var isPanelVisible
        get() = _isPanelVisible
        set(value) {
            _isPanelVisible = value
        }

    private var _isSettingsVisible by mutableStateOf(false)
    var isSettingsVisible
        get() = _isSettingsVisible
        set(value) {
            _isSettingsVisible = value
        }

    private var _isTempPanelVisible by mutableStateOf(false)
    var isTempPanelVisible
        get() = _isTempPanelVisible
        set(value) {
            _isTempPanelVisible = value
        }

    private var _isQuickPanelVisible by mutableStateOf(false)
    var isQuickPanelVisible
        get() = _isQuickPanelVisible
        set(value) {
            _isQuickPanelVisible = value
        }

    init {
        changeCurrentIptv(iptvGroupList.iptvList.getOrElse(SP.iptvLastIptvIdx) {
            iptvGroupList.firstOrNull()?.iptvList?.firstOrNull() ?: Iptv()
        })

        videoPlayerState.onReady {
            coroutineScope.launch {
                val name = _currentIptv.name
                val urlIdx = _currentIptvUrlIdx
                delay(Constants.UI_TEMP_PANEL_SCREEN_SHOW_DURATION)
                if (name == _currentIptv.name && urlIdx == _currentIptvUrlIdx) {
                    _isTempPanelVisible = false
                }
            }

            // 记忆可播放的域名（网页线路不是真实流，不参与记忆）
            rememberPlayableHost(_currentIptv.urlList[_currentIptvUrlIdx])
        }

        videoPlayerState.onError {
            if (_currentIptvUrlIdx < _currentIptv.urlList.size - 1) {
                changeCurrentIptv(_currentIptv, _currentIptvUrlIdx + 1)
            }

            // 从记忆中删除不可播放的域名（网页线路不在记忆范围内）
            forgetPlayableHost(_currentIptv.urlList[_currentIptvUrlIdx])
        }

        videoPlayerState.onCutoff {
            changeCurrentIptv(_currentIptv, _currentIptvUrlIdx)
        }
    }

    private fun getPrevIptv(): Iptv {
        val currentIndex = iptvGroupList.iptvIdx(_currentIptv)
        return iptvGroupList.iptvList.getOrElse(currentIndex - 1) {
            iptvGroupList.lastOrNull()?.iptvList?.lastOrNull() ?: Iptv()
        }
    }

    private fun getNextIptv(): Iptv {
        val currentIndex = iptvGroupList.iptvIdx(_currentIptv)
        return iptvGroupList.iptvList.getOrElse(currentIndex + 1) {
            iptvGroupList.firstOrNull()?.iptvList?.firstOrNull() ?: Iptv()
        }
    }

    /**
     * 切换频道或线路。
     *
     * @param urlIdx       指定线路；为 null 时先看"用户是否给这个台指定过网页"，再按记得能播的域名选
     * @param userSelected 是否由用户主动操作（左右键 / 线路列表）。只有主动选择才写入网页偏好，
     *                     自动降级（onError 往后推进）不能写，否则一次失败就把这个台永久钉在网页上。
     */
    fun changeCurrentIptv(iptv: Iptv, urlIdx: Int? = null, userSelected: Boolean = false) {
        _isPanelVisible = false

        if (iptv == _currentIptv && urlIdx == null) return

        // 只有"同一频道内主动换线路"才需要把旧线路的域名移出记忆；
        // 换频道时 urlIdx 为 null，此前这里用 `urlIdx != _currentIptvUrlIdx` 会恒成立，
        // 导致每换一次台就把上一个台的记忆删掉。
        if (iptv == _currentIptv && urlIdx != null && urlIdx != _currentIptvUrlIdx) {
            forgetPlayableHost(_currentIptv.urlList[_currentIptvUrlIdx])
        }

        _isTempPanelVisible = true

        _currentIptv = iptv
        SP.iptvLastIptvIdx = iptvGroupList.iptvIdx(_currentIptv)

        _currentIptvUrlIdx = if (urlIdx == null) {
            pickUrlIdx(_currentIptv)
        } else {
            (urlIdx + _currentIptv.urlList.size) % _currentIptv.urlList.size
        }

        val url = iptv.urlList[_currentIptvUrlIdx]
        log.d("播放${iptv.name}（${_currentIptvUrlIdx + 1}/${_currentIptv.urlList.size}）: $url")

        if (userSelected) rememberWebPreference(_currentIptv, url)

        videoPlayerState.prepare(url)
    }

    /**
     * 选线路的优先级：
     * 1. 用户主动给这个台指定过网页 → 就用网页，跨启动保持；
     * 2. 否则沿用"记得能播的域名"，网页线路永远不参与这个记忆（它是最后的兜底）。
     */
    private fun pickUrlIdx(iptv: Iptv): Int {
        val webIdx = iptv.urlList.indexOfFirst { it.startsWith(CctvWebChannels.WEB_SCHEME) }

        if (webIdx >= 0 && SP.iptvChannelWebPreferred.contains(iptv.channelName)) return webIdx

        return max(0, iptv.urlList.indexOfFirst {
            !it.startsWith(CctvWebChannels.WEB_SCHEME) &&
                    SP.iptvPlayableHostList.contains(getUrlHost(it))
        })
    }

    /** 用户主动选线路时，记住或撤销"这个台用网页" */
    private fun rememberWebPreference(iptv: Iptv, url: String) {
        val preferred = SP.iptvChannelWebPreferred
        SP.iptvChannelWebPreferred = if (url.startsWith(CctvWebChannels.WEB_SCHEME)) {
            preferred + iptv.channelName
        } else {
            preferred - iptv.channelName
        }
    }

    fun changeCurrentIptvToPrev() {
        changeCurrentIptv(getPrevIptv())
    }

    fun changeCurrentIptvToNext() {
        changeCurrentIptv(getNextIptv())
    }
}

@Composable
fun rememberLeanbackMainContentState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    videoPlayerState: LeanbackVideoPlayerState = rememberLeanbackVideoPlayerState(),
    iptvGroupList: IptvGroupList = IptvGroupList(),
) = remember {
    LeanbackMainContentState(
        coroutineScope = coroutineScope,
        videoPlayerState = videoPlayerState,
        iptvGroupList = iptvGroupList,
    )
}

/** 网页线路的伪 host：真实域名不可能包含 "://"，因此永远不会与它们冲突 */
private const val WEB_URL_HOST = "web://"

private fun getUrlHost(url: String): String {
    // web://https://tv.cctv.com/... 直接 split("://") 的第二段是 "https"，
    // 一旦混进"可播放域名"记忆，其他频道原生线路没成功过时就会误选网页线路。
    if (url.startsWith(CctvWebChannels.WEB_SCHEME)) return WEB_URL_HOST

    return url.split("://").getOrElse(1) { "" }.split("/").firstOrNull() ?: url
}

/** 记录可播放域名；网页线路不是真实流，不参与记忆 */
private fun rememberPlayableHost(url: String) {
    if (url.startsWith(CctvWebChannels.WEB_SCHEME)) return
    SP.iptvPlayableHostList += getUrlHost(url)
}

private fun forgetPlayableHost(url: String) {
    if (url.startsWith(CctvWebChannels.WEB_SCHEME)) return
    SP.iptvPlayableHostList -= getUrlHost(url)
}