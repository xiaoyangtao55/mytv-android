package top.yogiczy.mytv.ui.screens.leanback.video

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 网页播放页。
 *
 * 为什么不是"把网页塞进 Media3"：央视网页面自带播放器（HLS + MSE），
 * 不能复用 ExoPlayer 的 SurfaceView。这里退回到最朴素的做法 —— WebView 自己渲染，
 * 再用一段脚本把 <video> 铺满全屏、隐藏网页的头部和侧栏。
 *
 * 与原生线路共用同一套状态机：加载完成调 [LeanbackVideoPlayerState.notifyWebPageFinished]，
 * 失败调 [LeanbackVideoPlayerState.notifyWebPageError]，上层因此能自动切下一条线路。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeanbackWebVideoScreen(
    url: String,
    state: LeanbackVideoPlayerState,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            // 少数老盒子/精简 ROM 没有 WebView 实现，构造就会抛异常，兜住它
            runCatching {
                WebView(context).apply {
                setBackgroundColor(Color.BLACK)

                // 焦点全部留给 Compose：否则方向键会被网页吃掉，面板按键失效
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // 直播页需要自动起播，不能要求先有一次点击
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = DESKTOP_UA
                }

                webViewClient = object : WebViewClient() {
                    /**
                     * 核心：把页面播放器请求的媒体列表截下来，交给 ExoPlayer 播。
                     *
                     * 为什么不让 WebView 自己播：盒子的系统 WebView 经常缺 MSE 或硬解，
                     * 页面能渲染、节目单能出来，但 <video> 就是一片黑（实测就是这样）。
                     * 流地址本身是好的，换 ExoPlayer 硬解就没问题。
                     *
                     * 这里只旁路记录，返回 null 表示请求照常放行。
                     */
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val requestUrl = request.url?.toString().orEmpty()
                        if (isStreamPlaylist(requestUrl)) {
                            // 用目标页面地址而不是 view.url：加载/重定向过程中 view.url 可能还是旧值
                            // 回调会改 Compose 状态，必须回主线程；同一路流可能被请求多次，
                            // 重复触发由状态机里的冷却窗口挡住
                            view.post {
                                state.onWebStreamFound(requestUrl, refererOf(url), url)
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        state.notifyWebPageFinished()
                        view.evaluateJavascript(FILL_SCREEN_JS, null)
                    }

                    /**
                     * 渲染进程崩溃（内存不足、老旧 WebView、硬件加速异常都会触发）。
                     * 默认行为是把整个 App 一起带走 —— 必须返回 true 表示"我自己处理了"，
                     * 否则用户看到的就是"看着看着闪退"。
                     */
                    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        // 只上报；由状态驱动 VideoScreen 把整个 WebView 从组合里移除，
                        // 否则残留的 surface 会盖住错误提示
                        state.notifyWebPageError("网页渲染进程崩溃")
                        return true
                    }

                    @Deprecated("API < 23 的回调")
                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        if (errorCode < 0) return
                        state.notifyWebPageError(description ?: "未知错误")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        // 只有主文档失败才算这个频道挂了；子资源（广告/统计）失败忽略
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !request.isForMainFrame) return
                        state.notifyWebPageError(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error.description?.toString() ?: "未知错误"
                            } else {
                                "加载失败"
                            }
                        )
                    }
                }

                webChromeClient = WebChromeClient()

                state.attachWebController(object : WebVideoController {
                    override fun play() {
                        evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();", null)
                    }

                    override fun pause() {
                        evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.pause();})();", null)
                    }

                    override fun release() {
                        // 抓流成功后这个 WebView 会立刻被销毁，此时再 loadUrl 可能抛异常；
                        // 而且 about:blank 会再触发一次 onPageFinished，把刚切走的原生播放又"通知"一遍
                        runCatching {
                            // 换成空 client：之后 about:blank 的 onPageFinished 不会再回调到状态机
                            webViewClient = WebViewClient()
                            stopLoading()
                            loadUrl("about:blank")
                        }
                    }
                })

                webView = this
                tag = url
                loadUrl(url)
                }
            }.getOrElse {
                state.notifyWebPageError("系统 WebView 不可用，无法播放网页频道")
                View(context).apply { setBackgroundColor(Color.BLACK) }
            }
        },
        update = { view ->
            if (view is WebView && view.tag != url) {
                view.tag = url
                view.loadUrl(url)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            state.attachWebController(null)
            // 抓到流之后会立刻销毁 WebView，此时可能还有请求在飞，destroy 有一定概率抛异常
            runCatching {
                webView?.apply {
                    // 换成空 client：之后 about:blank 的 onPageFinished 不会再回调到状态机
                    webViewClient = WebViewClient()
                    stopLoading()
                    loadUrl("about:blank")
                    (parent as? ViewGroup)?.removeView(this)
                    destroy()
                }
            }
            webView = null
        }
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * 是否是"主播放列表"。
 *
 * 只认主列表，不认央视的码率子列表（带 `BR=`）和 ts 分片：
 * 主列表交给 ExoPlayer 后它会自己做码率自适应，片源更稳。
 */
internal fun isStreamPlaylist(url: String): Boolean {
    if (!url.startsWith("http")) return false
    val path = url.substringBefore('?').lowercase()
    if (!path.endsWith(".m3u8") && !path.endsWith(".flv")) return false
    return !url.contains("BR=")
}

/** 取页面 origin 作为 Referer：荔枝网这类源缺了它直接 403 */
internal fun refererOf(pageUrl: String): String? {
    val schemeEnd = pageUrl.indexOf("://")
    if (schemeEnd <= 0) return null

    val rest = pageUrl.substring(schemeEnd + 3)
    val authority = rest.substringBefore('/').substringBefore('?')
    if (authority.isEmpty()) return null

    return pageUrl.substring(0, schemeEnd + 3) + authority + "/"
}

/**
 * 等播放器初始化后：起播 + 把 <video> 铺满视口。
 * 用 CSS 而不是 Fullscreen API —— 后者会触发 onShowCustomView，
 * 在 TV 上还要额外处理返回键，得不偿失。
 *
 * 注意顺序：**先铺满再等画面**。原来一旦 videoWidth 还是 0 就直接 return，
 * 结果流没起播时连铺满都不做，用户看到的是网页原始布局（左侧一块黑）。
 */
private const val FILL_SCREEN_JS = """
(function () {
  var tries = 0;
  function tick() {
    if (tries++ > 60) return;
    var v = document.querySelector('video');
    if (!v) { setTimeout(tick, 700); return; }

    v.style.position = 'fixed';
    v.style.left = '0';
    v.style.top = '0';
    v.style.width = '100vw';
    v.style.height = '100vh';
    v.style.zIndex = '2147483647';
    v.style.background = '#000';
    v.removeAttribute('controls');
    document.documentElement.style.overflow = 'hidden';
    document.body.style.overflow = 'hidden';

    if (!v.videoWidth) { setTimeout(tick, 700); return; }

    try {
      v.muted = false;
      v.volume = 1;
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    } catch (e) {}
  }
  tick();
})();
"""
