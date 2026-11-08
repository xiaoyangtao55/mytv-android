package top.yogiczy.mytv.ui.screens.leanback.video

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.yogiczy.mytv.data.repositories.iptv.CctvWebChannels
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackMedia3VideoPlayer
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer

/**
 * 播放器状态
 */
@Stable
class LeanbackVideoPlayerState(
    private val instance: LeanbackVideoPlayer,
    private val defaultAspectRatioProvider: () -> Float? = { null },
) {
    /** 视频宽高比 */
    var aspectRatio by mutableFloatStateOf(16f / 9f)

    /** 错误 */
    var error by mutableStateOf<String?>(null)

    /** 元数据 */
    var metadata by mutableStateOf(LeanbackVideoPlayer.Metadata())

    /**
     * 当前频道走网页播放时的地址（已去掉 web:// 前缀）。
     * 非空表示这一路不是 ExoPlayer 在渲染，VideoScreen 要换成 WebView。
     */
    var webUrl by mutableStateOf<String?>(null)
        private set

    /** 网页是否还在加载（用于面板收起时机） */
    var webLoading by mutableStateOf(false)
        private set

    /**
     * 网页是否已经失败。
     * 失败时必须把 WebView 从组合里摘掉：崩溃的渲染进程会留下一块黑色 surface，
     * 盖住 Compose 画的错误提示，用户只看到黑屏。
     */
    var webFailed by mutableStateOf(false)
        private set

    /** WebView 侧的控制句柄，由 VideoScreen 挂上来 */
    private var webController: WebVideoController? = null

    fun attachWebController(controller: WebVideoController?) {
        webController = controller
    }

    fun prepare(url: String) {
        error = null

        if (url.startsWith(CctvWebChannels.WEB_SCHEME)) {
            webUrl = url.removePrefix(CctvWebChannels.WEB_SCHEME)
            webLoading = true
            webFailed = false
            // 停掉原生播放器：换到网页频道后它不该继续在后台缓冲
            instance.pause()
        } else {
            webUrl = null
            webLoading = false
            instance.prepare(url)
        }
    }

    fun play() {
        if (webUrl != null) {
            webController?.play()
        } else {
            instance.play()
        }
    }

    fun pause() {
        if (webUrl != null) {
            webController?.pause()
        } else {
            instance.pause()
        }
    }

    /** 网页加载完成：等价于原生播放器的 onReady */
    fun notifyWebPageFinished() {
        webLoading = false
        webFailed = false
        error = null
        onReadyListeners.forEach { it.invoke() }
    }

    /** 网页加载失败：交给上层切下一条线路 */
    fun notifyWebPageError(message: String) {
        webLoading = false
        webFailed = true
        error = "网页加载失败：$message"
        onErrorListeners.forEach { it.invoke() }
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        instance.setVideoSurfaceView(surfaceView)
    }

    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onErrorListeners = mutableListOf<() -> Unit>()
    private val onCutoffListeners = mutableListOf<() -> Unit>()

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onError(listener: () -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onCutoff(listener: () -> Unit) {
        onCutoffListeners.add(listener)
    }

    fun initialize() {
        instance.initialize()
        instance.onResolution { width, height ->
            val defaultAspectRatio = defaultAspectRatioProvider()

            if (defaultAspectRatio == null) {
                if (width > 0 && height > 0) aspectRatio = width.toFloat() / height
            } else {
                aspectRatio = defaultAspectRatio
            }
        }
        instance.onError { ex ->
            error = if (ex != null) "${ex.errorCodeName}(${ex.errorCode})"
            else null

            if (error != null) onErrorListeners.forEach { it.invoke() }

        }
        instance.onReady { onReadyListeners.forEach { it.invoke() } }
        instance.onBuffering { if (it) error = null }
        instance.onPrepared { }
        instance.onMetadata { metadata = it }
        instance.onCutoff { onCutoffListeners.forEach { it.invoke() } }
    }

    fun release() {
        onReadyListeners.clear()
        onErrorListeners.clear()
        webController?.release()
        webController = null
        webUrl = null
        instance.release()
    }
}

@Composable
fun rememberLeanbackVideoPlayerState(
    defaultAspectRatioProvider: () -> Float? = { null },
): LeanbackVideoPlayerState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val state = remember {
        LeanbackVideoPlayerState(
            LeanbackMedia3VideoPlayer(context, coroutineScope),
            defaultAspectRatioProvider = defaultAspectRatioProvider,
        )
    }

    DisposableEffect(Unit) {
        state.initialize()

        onDispose {
            state.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.play()
            } else if (event == Lifecycle.Event.ON_STOP) {
                state.pause()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return state
}

/** 网页播放的控制句柄，由 VideoScreen 里的 WebView 实现 */
interface WebVideoController {
    fun play()
    fun pause()
    fun release()
}
