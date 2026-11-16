package com.wavecue.app.playback

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.wavecue.core.audio.AudioEngineConfig
import com.wavecue.core.audio.EngineEvent
import com.wavecue.core.audio.PlaybackEngine
import com.wavecue.core.data.repo.LibraryRepository
import com.wavecue.core.data.repo.SettingsRepository
import com.wavecue.core.model.LoadedMedia
import com.wavecue.core.model.PlaybackDiagnostics
import com.wavecue.core.model.PlaybackState
import com.wavecue.core.model.QueueItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the queue and the playback session. Audio focus is requested per play; pausing
 * releases focus and no ducking is ever applied, because ducking modifies the signal.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    val engine: PlaybackEngine,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val _index = MutableStateFlow(-1)
    private val _queueLabel = MutableStateFlow<String?>(null)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val queue: StateFlow<List<QueueItem>> = _queue
    val queueLabel: StateFlow<String?> = _queueLabel
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val current: StateFlow<QueueItem?> =
        combine(_queue, _index) { queue, index -> queue.getOrNull(index) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<PlaybackState> = engine.state
    val positionMs: StateFlow<Long> = engine.positionMs
    val media: StateFlow<LoadedMedia?> = engine.media
    val diagnostics: StateFlow<PlaybackDiagnostics> = engine.diagnostics

    private var resumeOnFocusGain = false
    private var hasFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                hasFocus = false
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = engine.state.value == PlaybackState.PLAYING
                hasFocus = false
                // Pausing rather than ducking keeps the signal untouched.
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    engine.play()
                }
            }
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                engine.pause()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch {
            settings.settings.collect { values ->
                engine.setConfig(
                    AudioEngineConfig(
                        bufferMs = values.bufferMs,
                        exclusiveMode = values.exclusiveMode,
                        bitPerfectUsb = values.bitPerfectUsb,
                    )
                )
            }
        }

        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    EngineEvent.TrackEnded -> autoAdvance()
                    is EngineEvent.Error -> _errors.tryEmit(event.message)
                }
            }
        }

        scope.launch {
            engine.media.collect { loaded ->
                if (loaded == null) return@collect
                val item = current.value ?: return@collect
                library.applyLoadedMedia(item.trackId, loaded)
            }
        }
    }

    fun playQueue(items: List<QueueItem>, startIndex: Int, label: String?) {
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        _queue.value = items
        _index.value = index
        _queueLabel.value = label
        playCurrent()
    }

    fun playPlaylist(items: List<QueueItem>, startIndex: Int, playlistName: String) {
        playQueue(items, startIndex, "Playlist: $playlistName")
    }

    fun toggle() {
        when (engine.state.value) {
            PlaybackState.PLAYING -> {
                engine.pause()
                abandonFocus()
            }
            PlaybackState.PAUSED -> {
                if (requestFocus()) engine.play()
            }
            PlaybackState.ENDED -> {
                if (requestFocus()) {
                    engine.seekTo(0)
                    engine.play()
                }
            }
            PlaybackState.ERROR, PlaybackState.IDLE -> playCurrent()
            PlaybackState.LOADING -> Unit
        }
    }

    fun play() {
        if (requestFocus()) {
            if (engine.state.value == PlaybackState.ENDED) engine.seekTo(0)
            engine.play()
        }
    }

    fun pause() {
        engine.pause()
        abandonFocus()
    }

    fun next() {
        val queue = _queue.value
        val index = _index.value
        if (index + 1 in queue.indices) {
            _index.value = index + 1
            playCurrent()
        }
    }

    fun previous() {
        when {
            engine.positionMs.value > 3_000 -> engine.seekTo(0)
            _index.value > 0 -> {
                _index.value = _index.value - 1
                playCurrent()
            }
            else -> engine.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) {
        engine.seekBy(deltaMs)
    }

    fun select(index: Int) {
        if (index in _queue.value.indices) {
            _index.value = index
            playCurrent()
        }
    }

    private fun playCurrent() {
        val item = current.value ?: return
        if (!requestFocus()) {
            _errors.tryEmit("Audio focus is unavailable right now")
            return
        }
        engine.load(Uri.parse(item.uri), item.name, autoPlay = true)
        PlaybackService.start(context)
    }

    private fun autoAdvance() {
        val queue = _queue.value
        val index = _index.value
        if (index + 1 in queue.indices) {
            _index.value = index + 1
            playCurrent()
        }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        if (hasFocus) return true
        val result = manager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }
}
