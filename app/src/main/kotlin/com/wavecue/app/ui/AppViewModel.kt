package com.wavecue.app.ui

import androidx.lifecycle.ViewModel
import com.wavecue.app.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val controller: PlaybackController,
) : ViewModel() {
    val current = controller.current
    val state = controller.state
    val media = controller.media
    val positionMs = controller.positionMs
    val queueLabel = controller.queueLabel
    val errors = controller.errors
}
