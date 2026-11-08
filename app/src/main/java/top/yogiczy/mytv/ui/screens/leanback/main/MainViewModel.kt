package top.yogiczy.mytv.ui.screens.leanback.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    init {
        viewModelScope.launch {
            refreshIptv()
            refreshEpg()
        }
    }

    private suspend fun refreshIptv() {
        val sourceUrl = SP.iptvSourceUrl

        // 没配源（出厂占位符 1.2.3.4）就直接用内置央视网页频道，不要让用户干等 30 秒重试
        if (sourceUrl.isBlank() || sourceUrl.contains("1.2.3.4")) {
            _uiState.value = LeanbackMainUiState.Ready(iptvGroupList = CctvWebChannels.fallback())
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

                _uiState.value =
                    LeanbackMainUiState.Loading("获取远程直播源(${attempt + 1}/$FALLBACK_RETRY_COUNT)...")
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            .map { source -> CctvWebChannels.merge(source) }
            .catch {
                // 源挂了也要能看电视：直接进内置频道，而不是甩一个错误页
                _uiState.value = LeanbackMainUiState.Ready(
                    iptvGroupList = CctvWebChannels.fallback(),
                    notice = "订阅源不可用，已切换为内置央视网页频道",
                )
                SP.iptvSourceUrlHistoryList -= sourceUrl
            }
            .map { merged ->
                if (merged.isNotEmpty()) {
                    _uiState.value = LeanbackMainUiState.Ready(iptvGroupList = merged)
                    SP.iptvSourceUrlHistoryList += sourceUrl
                } else {
                    _uiState.value = LeanbackMainUiState.Ready(
                        iptvGroupList = CctvWebChannels.fallback(),
                        notice = "订阅源没有解析出频道，已切换为内置央视网页频道",
                    )
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
                    _uiState.value =
                        (_uiState.value as LeanbackMainUiState.Ready).copy(epgList = epgList)
                    SP.epgXmlUrlHistoryList += SP.epgXmlUrl
                }
                .collect()
        }
    }
}

sealed interface LeanbackMainUiState {
    data class Loading(val message: String? = null) : LeanbackMainUiState
    data class Error(val message: String? = null) : LeanbackMainUiState
    data class Ready(
        val iptvGroupList: IptvGroupList = IptvGroupList(),
        val epgList: EpgList = EpgList(),
        /** 降级说明，非空时应在界面上提示一次 */
        val notice: String? = null,
    ) : LeanbackMainUiState
}

/** 配了订阅源但拉取失败时，最多重试几次就回落到内置频道 */
private const val FALLBACK_RETRY_COUNT = 3L