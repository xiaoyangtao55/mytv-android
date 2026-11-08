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
                        stopLoading()
                        loadUrl("about:blank")
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
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * 等播放器初始化后：起播 + 把 <video> 铺满视口。
 * 用 CSS 而不是 Fullscreen API —— 后者会触发 onShowCustomView，
 * 在 TV 上还要额外处理返回键，得不偿失。
 */
private const val FILL_SCREEN_JS = """
(function () {
  var tries = 0;
  function tick() {
    if (tries++ > 30) return;
    var v = document.querySelector('video');
    if (!v || !v.videoWidth) { setTimeout(tick, 700); return; }
    try {
      v.muted = false;
      v.volume = 1;
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    } catch (e) {}
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
  }
  tick();
})();
"""
