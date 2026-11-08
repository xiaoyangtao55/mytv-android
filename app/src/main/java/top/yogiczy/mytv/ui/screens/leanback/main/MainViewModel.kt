package top.yogiczy.mytv.ui.screens.leanback.main

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvList
import top.yogiczy.mytv.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.data.repositories.iptv.CctvWebChannels
import top.yogiczy.mytv.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.utils.SP

class LeanbackMainViewModel : ViewModel() {
    private val iptvRepository = IptvRepository()
    private val epgRepository = EpgRepository()

    private val _uiState = MutableStateFlow<LeanbackMainUiState>(LeanbackMainUiState.Loading())
    val uiState: StateFlow<LeanbackMainUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var iptvListListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    init {
        reloadIptv()

        // 设置页和主界面共用一个 Activity，ViewModel 常驻；
        // 没有这个监听，改完开关/订阅源要杀进程才生效
        iptvListListener = SP.addIptvListChangeListener {
            reloadIptv()
        }
    }

    override fun onCleared() {
        iptvListListener?.let { SP.removeListener(it) }
        iptvListListener = null
        super.onCleared()
    }

    /**
     * 重建频道列表。
     *
     * 已有内容时不显示"加载中"：用户在设置里拨一下开关，不该看到界面先闪回加载页
     * 再等源重试九秒。
     */
    private fun reloadIptv() {
        val quiet = _uiState.value is LeanbackMainUiState.Ready

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshIptv(quiet = quiet)
            refreshEpg()
        }
    }

    private suspend fun refreshIptv(quiet: Boolean = false) {
        val sourceUrl = SP.iptvSourceUrl
        val webFallback = SP.iptvWebFallbackEnable

        // 没配源（出厂占位符 1.2.3.4）直接用内置央视网页频道，不要让用户干等重试
        val hasSource = sourceUrl.isNotBlank() && !sourceUrl.contains("1.2.3.4")
        if (!hasSource) {
            _uiState.value = if (webFallback) {
                LeanbackMainUiState.Ready(iptvGroupList = CctvWebChannels.fallback())
            } else {
                LeanbackMainUiState.Empty(EMPTY_NO_SOURCE)
            }
            return
        }

        flow {
            emit(
                iptvRepository.getIptvGroupList(
                    sourceUrl = sourceUrl,
                    cacheTime = SP.iptvSourceCacheTime,
                    simplify = SP.iptvSourceSimplify,
                )
            )
        }
            .retryWhen { _, attempt ->
                // 有内置频道兜底，没必要重试 10 次 —— 3 次（约 9 秒）足够区分"源慢"和"源挂"
                if (attempt >= FALLBACK_RETRY_COUNT) return@retryWhen false

                if (!quiet) {
                    _uiState.value =
                        LeanbackMainUiState.Loading("获取远程直播源(${attempt + 1}/$FALLBACK_RETRY_COUNT)...")
                }
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            // 开关关闭时一个频道都不动，列表严格等于用户自己的源
            .map { source -> if (webFallback) CctvWebChannels.merge(source) else source }
            .catch {
                // 静默刷新失败就保留界面上已有的内容，别把用户在看的台顶掉
                if (quiet) return@catch

                _uiState.value = if (webFallback) {
                    LeanbackMainUiState.Ready(
                        iptvGroupList = CctvWebChannels.fallback(),
                        notice = "订阅源不可用，已切换为内置央视网页频道",
                    )
                } else {
                    LeanbackMainUiState.Empty(EMPTY_SOURCE_FAILED)
                }
                SP.iptvSourceUrlHistoryList -= sourceUrl
            }
            .map { merged ->
                if (merged.isNotEmpty()) {
                    _uiState.value = LeanbackMainUiState.Ready(iptvGroupList = merged)
                    SP.iptvSourceUrlHistoryList += sourceUrl
                } else {
                    _uiState.value = if (webFallback) {
                        LeanbackMainUiState.Ready(
                            iptvGroupList = CctvWebChannels.fallback(),
                            notice = "订阅源没有解析出频道，已切换为内置央视网页频道",
                        )
                    } else {
                        LeanbackMainUiState.Empty(EMPTY_SOURCE_EMPTY)
                    }
                }
                merged
            }
            .collect()
    }

    private suspend fun refreshEpg() {
        if (_uiState.value is LeanbackMainUiState.Ready) {
            val iptvGroupList = (_uiState.value as LeanbackMainUiState.Ready).iptvGroupList

            flow {
                emit(
                    epgRepository.getEpgList(
                        xmlUrl = SP.epgXmlUrl,
                        filteredChannels = iptvGroupList.iptvList.map { it.channelName },
                        refreshTimeThreshold = SP.epgRefreshTimeThreshold,
                    )
                )
            }
                .retry(Constants.HTTP_RETRY_COUNT) { delay(Constants.HTTP_RETRY_INTERVAL); true }
                .catch {
                    emit(EpgList())
                    SP.epgXmlUrlHistoryList -= SP.epgXmlUrl
                }
                .map { epgList ->
                    // 期间列表可能已被下一次刷新替换掉，状态不是 Ready 就丢弃这次结果
                    val current = _uiState.value
                    if (current is LeanbackMainUiState.Ready) {
                        _uiState.value = current.copy(epgList = epgList)
                    }
                    SP.epgXmlUrlHistoryList += SP.epgXmlUrl
                }
                .collect()
        }
    }
}

sealed interface LeanbackMainUiState {
    data class Loading(val message: String? = null) : LeanbackMainUiState
    data class Error(val message: String? = null) : LeanbackMainUiState

    /** 没有任何可播放的频道：没配源，且央视网页兜底被关掉了 */
    data class Empty(val message: String) : LeanbackMainUiState

    data class Ready(
        val iptvGroupList: IptvGroupList = IptvGroupList(),
        val epgList: EpgList = EpgList(),
        /** 降级说明，非空时应在界面上提示一次 */
        val notice: String? = null,
    ) : LeanbackMainUiState
}

/** 配了订阅源但拉取失败时，最多重试几次就回落到内置频道 */
private const val FALLBACK_RETRY_COUNT = 3L

private const val EMPTY_NO_SOURCE =
    "还没有配置直播源。按菜单键进设置添加，或开启「央视网页兜底」。"

private const val EMPTY_SOURCE_FAILED =
    "订阅源拉取失败。按菜单键进设置检查网络或换源，也能开启「央视网页兜底」先看央视。"

private const val EMPTY_SOURCE_EMPTY =
    "订阅源里没有频道。按菜单键进设置检查地址，也能开启「央视网页兜底」先看央视。"
