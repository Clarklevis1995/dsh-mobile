package com.clarklevis.dsh.android.ui

import android.widget.VideoView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle

/** 持有当前预览的播放器；保存时只保留进度，不保存 View 或 Activity。 */
@Stable
internal class WorkspaceVideoPreviewState(initialPosition: Int = 0) {
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(initialPosition)
        private set
    private var video: VideoView? = null
    private var suspended = false
    private var seeking = false

    fun attach(player: VideoView) {
        video = player
        player.setOnPreparedListener { media ->
            duration = media.duration.coerceAtLeast(0)
            ready = true
            player.seekTo(position.coerceIn(0, duration).coerceAtLeast(1))
        }
        player.setOnCompletionListener {
            playing = false
            position = duration
        }
        player.setOnErrorListener { _, _, _ ->
            failed = true
            ready = false
            playing = false
            true
        }
    }

    fun onLifecycle(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_PAUSE -> {
                if (ready) position = video?.currentPosition ?: position
                video?.pause()
                playing = false
            }
            Lifecycle.Event.ON_STOP -> {
                video?.suspend()
                ready = false
                suspended = true
            }
            Lifecycle.Event.ON_START -> if (suspended) {
                suspended = false
                video?.resume()
            }
            else -> Unit
        }
    }

    fun pollPosition() {
        if (!seeking) position = video?.currentPosition ?: position
        playing = video?.isPlaying == true
    }

    fun togglePlayback() {
        video?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                if (position >= duration) player.seekTo(0)
                player.start()
            }
            playing = player.isPlaying
        }
    }

    fun seek(value: Float) {
        seeking = true
        position = value.toInt()
    }

    fun finishSeeking() {
        video?.seekTo(position)
        seeking = false
    }

    fun release() {
        video?.apply {
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            stopPlayback()
        }
        video = null
        ready = false
        playing = false
    }

    companion object {
        val saver = Saver<WorkspaceVideoPreviewState, Int>(save = { it.position }, restore = { WorkspaceVideoPreviewState(it) })
    }
}
