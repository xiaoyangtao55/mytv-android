package top.yogiczy.mytv.ui.screens.leanback.video

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.screens.leanback.video.components.LeanbackVideoPlayerMetadata

@Composable
fun LeanbackVideoScreen(
    modifier: Modifier = Modifier,
    state: LeanbackVideoPlayerState = rememberLeanbackVideoPlayerState(),
    showMetadataProvider: () -> Boolean = { false },
) {
    val context = LocalContext.current
    val childPadding = rememberLeanbackChildPadding()

    Box(modifier = modifier.fillMaxSize()) {
        val webUrl = state.webUrl
        if (webUrl != null && !state.webFailed) {
            // 网页频道：由 WebView 自己渲染，不需要 SurfaceView
            LeanbackWebVideoScreen(
                url = webUrl,
                state = state,
            )
        } else if (webUrl != null) {
            // 网页已失败：留一块纯黑，让错误提示浮在上面
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(state.aspectRatio),
                factory = {
                    // PlayerView 切换视频时黑屏闪烁，使用 SurfaceView 代替
                    SurfaceView(context)
                },
                update = { surfaceView ->
                    state.setVideoSurfaceView(surfaceView)
                },
            )
        }

        LeanbackVideoPlayerErrorScreen(
            errorProvider = { state.error },
        )

        if (showMetadataProvider()) {
            LeanbackVideoPlayerMetadata(
                modifier = Modifier.padding(start = childPadding.start, top = childPadding.top),
                metadata = state.metadata,
            )
        }
    }
}