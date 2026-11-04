package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun WorkspaceImagePreviewContent(file: AndroidWorkspaceLocalFile, onOpenExternal: () -> Unit, contentPadding: PaddingValues) {
    var bitmap by remember(file.file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.file) { mutableStateOf(false) }
    LaunchedEffect(file.file) {
        try {
            bitmap = withContext(Dispatchers.IO) { decodeWorkspaceImage(file.file, file.name, file.mediaType) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    Box(Modifier.fillMaxSize().padding(contentPadding).clipToBounds(), contentAlignment = Alignment.Center) {
        when {
            failed -> MediaPreviewError(appText("无法预览图片"), onOpenExternal)
            bitmap != null -> Box(Modifier.fillMaxSize().testTag("workspace-image-preview")) {
                ZoomablePreviewImage(requireNotNull(bitmap).asImageBitmap(), file.name)
            }
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
internal fun WorkspaceVideoPreviewContent(file: AndroidWorkspaceLocalFile, onOpenExternal: () -> Unit, contentPadding: PaddingValues) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = rememberSaveable(file.file.path, saver = WorkspaceVideoPreviewState.saver) { WorkspaceVideoPreviewState() }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> state.onLifecycle(event) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state, state.ready) {
        while (isActive && state.ready) {
            state.pollPosition()
            delay(250)
        }
    }
    if (state.failed) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            MediaPreviewError(appText("无法播放视频"), onOpenExternal)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(contentPadding).testTag("workspace-video-preview")) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        val player = VideoView(context)
                        state.attach(player)
                        player.setVideoURI(Uri.fromFile(file.file))
                        addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { state.release() }
            )
            if (!state.ready) CircularProgressIndicator()
        }
        WorkspaceVideoControls(state)
    }
}

@Composable
private fun WorkspaceVideoControls(state: WorkspaceVideoPreviewState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = state.ready,
            onClick = state::togglePlayback,
            modifier = Modifier.testTag("workspace-video-play-pause")
        ) {
            Icon(
                painterResource(if (state.playing) R.drawable.ic_pause_circle else R.drawable.ic_play_circle),
                appText(if (state.playing) "暂停" else "播放")
            )
        }
        val progressDescription = appText("播放进度")
        Slider(
            value = state.position.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
            onValueChange = state::seek,
            onValueChangeFinished = state::finishSeeking,
            valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(),
            enabled = state.ready && state.duration > 0,
            modifier = Modifier.weight(1f).testTag("workspace-video-progress")
                .semantics { contentDescription = progressDescription }
        )
        Text(
            "${videoTime(state.position)} / ${videoTime(state.duration)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private const val MILLIS_PER_MINUTE = 60_000
private const val MILLIS_PER_SECOND = 1_000
private const val SECONDS_PER_MINUTE = 60

private fun videoTime(milliseconds: Int): String = String.format(
    Locale.ROOT,
    "%d:%02d",
    milliseconds / MILLIS_PER_MINUTE,
    milliseconds / MILLIS_PER_SECOND % SECONDS_PER_MINUTE
)

@Composable
private fun MediaPreviewError(message: String, onOpenExternal: () -> Unit) {
    Column(
        Modifier.padding(24.dp).testTag("workspace-media-preview-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenExternal) { Text(appText("使用其他应用打开")) }
    }
}
