package com.wavecue.core.audio

import android.content.Context
import android.net.Uri
import com.wavecue.core.model.LoadedMedia
import com.wavecue.core.model.PlaybackDiagnostics
import com.wavecue.core.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class AudioEngineConfig(
    val bufferMs: Int = 300,
    val exclusiveMode: Boolean = true,
    val bitPerfectUsb: Boolean = false,
)

sealed interface EngineEvent {
    data object TrackEnded : EngineEvent
    data class Error(val message: String) : EngineEvent
}

/**
 * File playback engine. A single dedicated thread owns the decoder and the output;
 * UI commands are serialized through a queue so pause/seek never race with a write.
 */
class PlaybackEngine(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs
    private val _media = MutableStateFlow<LoadedMedia?>(null)
    val media: StateFlow<LoadedMedia?> = _media
    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EngineEvent> = _events

    private val commands = LinkedBlockingQueue<Command>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var config = AudioEngineConfig()

    private var source: PcmSource? = null
    private var output: AudioOutput? = null
    private var frameRate = 0
    private var channels = 1
    private var totalFrames = 0L
    private var baseFrame = 0L
    private var chunk = FloatArray(0)
    private var chunkFrames = 0
    private var needsRefill = false
    private var playing = false
    @Volatile private var closing = false
    @Volatile private var lastError: String? = null

    private val engineThread = Thread({ runLoop() }, "wavecue-audio-engine").apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    init {
        scope.launch {
            while (isActive) {
                publishPosition()
                delay(40)
            }
        }
    }

    fun setConfig(newConfig: AudioEngineConfig) {
        config = newConfig
    }

    fun load(uri: Uri, displayName: String, autoPlay: Boolean = true) {
        commands.put(Command.Load(uri, displayName, autoPlay))
    }

    fun play() {
        commands.put(Command.Play)
    }

    fun pause() {
        commands.put(Command.Pause)
    }

    fun seekTo(positionMs: Long) {
        commands.put(Command.Seek(positionMs.coerceAtLeast(0)))
    }

    fun seekBy(deltaMs: Long) {
        seekTo(_positionMs.value + deltaMs)
    }

    fun shutdown() {
        closing = true
        commands.put(Command.Release)
        scope.cancel()
    }

    private fun runLoop() {
        while (!closing) {
            val command = try {
                commands.poll(20, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (command != null) {
                process(command)
            }
            if (playing && _state.value == PlaybackState.PLAYING) {
                decodeOneChunk()
            }
        }
        closeCurrent()
    }

    private fun process(command: Command) {
        when (command) {
            is Command.Load -> handleLoad(command)
            Command.Play -> handlePlay()
            Command.Pause -> handlePause()
            is Command.Seek -> handleSeek(command.positionMs)
            Command.Release -> closeCurrent()
        }
    }

    private fun handleLoad(command: Command.Load) {
        closeCurrent()
        _state.value = PlaybackState.LOADING
        _media.value = null
        _positionMs.value = 0L
        lastError = null
        try {
            val newSource = PcmSourceFactory.open(appContext, command.uri, command.displayName)
            // Some decoders (MediaCodec) publish their real output rate and channel
            // count on the first buffer, not in the container. Probe once, then rewind,
            // so the sink and the engine agree on the actual format from frame zero.
            try {
                newSource.readFloat(FloatArray(8), 1)
            } catch (_: Throwable) {
                // A probe failure will resurface on the first real read.
            }
            newSource.seekToFrame(0)
            val newOutput = createOutput(newSource)
            source = newSource
            output = newOutput
            frameRate = newSource.sampleRate
            channels = newSource.channelCount
            totalFrames = newSource.durationFrames
            baseFrame = 0L
            chunkFrames = 2048
            chunk = FloatArray(chunkFrames * channels)
            newSource.seekToFrame(0)
            prefill()
            needsRefill = false
            _media.value = LoadedMedia(
                uri = command.uri.toString(),
                name = command.displayName,
                durationMs = newSource.durationMs,
                sampleRate = newSource.sampleRate,
                channelCount = newSource.channelCount,
                bitDepth = newSource.bitDepth,
                codec = newSource.codec,
                isFloat = newSource.isFloat,
            )
            publishDiagnostics()
            if (command.autoPlay) {
                newOutput.start()
                playing = true
                _state.value = PlaybackState.PLAYING
            } else {
                playing = false
                _state.value = PlaybackState.PAUSED
            }
        } catch (t: Throwable) {
            fail(t.message ?: "Cannot open audio file")
        }
    }

    private fun createOutput(source: PcmSource): AudioOutput {
        val settings = config
        if (settings.bitPerfectUsb) {
            val bitPerfect = AudioTrackOutput.createBitPerfect(
                context = appContext,
                sampleRate = source.sampleRate,
                channelCount = source.channelCount,
                sourceBitDepth = source.bitDepth,
                sourceIsFloat = source.isFloat,
            )
            if (bitPerfect != null) return bitPerfect
        }
        val aaudio = AaudioOutput.create(
            sampleRate = source.sampleRate,
            channels = source.channelCount,
            exclusive = settings.exclusiveMode,
        )
        if (aaudio != null) return aaudio
        val fallback = AudioTrackOutput.createSharedFloat(appContext, source.sampleRate, source.channelCount)
            ?: throw IllegalStateException("No audio output available for ${source.sampleRate} Hz")
        return fallback
    }

    private fun handlePlay() {
        val out = output ?: return
        if (source == null) return
        if (needsRefill) {
            source?.seekToFrame(baseFrame)
            prefill()
            needsRefill = false
        }
        out.start()
        playing = true
        _state.value = PlaybackState.PLAYING
    }

    private fun handlePause() {
        val out = output
        val src = source
        if (out == null || src == null) {
            playing = false
            _state.value = PlaybackState.PAUSED
            return
        }
        playing = false
        val heardFrame = currentFramePosition()
        try {
            out.pause()
            out.flush()
        } catch (_: Exception) {
            // the output may already be gone
        }
        baseFrame = heardFrame.coerceIn(0, if (totalFrames > 0) totalFrames else heardFrame)
        src.seekToFrame(baseFrame)
        needsRefill = true
        _positionMs.value = framesToMs(baseFrame)
        _state.value = PlaybackState.PAUSED
        publishDiagnostics()
    }

    private fun handleSeek(positionMs: Long) {
        val src = source
        val out = output
        if (src == null || out == null || frameRate <= 0) return
        val wasPlaying = playing
        playing = false
        try {
            out.pause()
            out.flush()
        } catch (_: Exception) {
            // ignore
        }
        val target = msToFrames(positionMs).let {
            if (totalFrames > 0) it.coerceIn(0, totalFrames) else it.coerceAtLeast(0)
        }
        src.seekToFrame(target)
        baseFrame = target
        prefill()
        needsRefill = false
        _positionMs.value = framesToMs(target)
        if (wasPlaying) {
            out.start()
            playing = true
            _state.value = PlaybackState.PLAYING
        } else {
            _state.value = PlaybackState.PAUSED
        }
        publishDiagnostics()
    }

    private fun decodeOneChunk() {
        val src = source ?: return
        val out = output ?: return
        val framesRead = try {
            src.readFloat(chunk, chunkFrames)
        } catch (t: Throwable) {
            fail(t.message ?: "Decode failed")
            return
        }
        if (framesRead <= 0) {
            finishTrack()
            return
        }
        var writtenSamples = 0
        val totalSamples = framesRead * channels
        var stalls = 0
        while (writtenSamples < totalSamples && playing && !closing) {
            val written = try {
                out.write(chunk, writtenSamples, totalSamples - writtenSamples)
            } catch (_: Exception) {
                0
            }
            if (written <= 0) {
                if (out.isDisconnected()) {
                    fail("Audio output disconnected")
                    return
                }
                if (++stalls > 500) {
                    fail("Audio output stalled")
                    return
                }
                Thread.sleep(1)
            } else {
                writtenSamples += written
                stalls = 0
            }
        }
    }

    private fun finishTrack() {
        playing = false
        val out = output
        var waited = 0
        while (out != null && out.bufferedFrames() > 0 && waited < 2500 && !closing) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
            waited += 20
        }
        try {
            out?.pause()
        } catch (_: Exception) {
            // ignore
        }
        if (totalFrames > 0) {
            baseFrame = totalFrames
            _positionMs.value = framesToMs(totalFrames)
        }
        _state.value = PlaybackState.ENDED
        publishDiagnostics()
        _events.tryEmit(EngineEvent.TrackEnded)
    }

    private fun prefill() {
        val src = source ?: return
        val out = output ?: return
        if (channels <= 0 || frameRate <= 0) return
        val desired = (frameRate.toLong() * config.bufferMs / 1000).toInt().coerceAtLeast(frameRate / 20)
        val target = minOf(desired, out.capacityFrames.coerceAtLeast(1))
        var filled = 0
        var stalls = 0
        while (filled < target && !closing) {
            val want = minOf(chunkFrames, target - filled)
            if (want <= 0) break
            val framesRead = src.readFloat(chunk, want)
            if (framesRead <= 0) break
            var writtenSamples = 0
            val totalSamples = framesRead * channels
            while (writtenSamples < totalSamples) {
                val written = out.write(chunk, writtenSamples, totalSamples - writtenSamples)
                if (written <= 0) {
                    if (++stalls > 200) return
                    Thread.sleep(1)
                } else {
                    writtenSamples += written
                    stalls = 0
                }
            }
            filled += framesRead
        }
    }

    private fun publishPosition() {
        val position = framesToMs(currentFramePosition())
        val max = if (totalFrames > 0) framesToMs(totalFrames) else Long.MAX_VALUE
        _positionMs.value = position.coerceIn(0, max)
        if (source != null) publishDiagnostics()
    }

    private fun publishDiagnostics() {
        val src = source
        val out = output
        if (src == null || out == null) {
            _diagnostics.value = PlaybackDiagnostics()
            return
        }
        val message = when {
            out.bitPerfect -> "Bit-perfect USB: mixer bypassed, no volume or DSP applied."
            out.sampleRate != src.sampleRate ->
                "System resampling ${src.sampleRate} Hz to ${out.sampleRate} Hz."
            out.sharingMode == "exclusive" -> "Exclusive output stream at the source rate."
            else -> "Shared output at the source rate. The system mixer may still convert for the device."
        }
        _diagnostics.value = PlaybackDiagnostics(
            sinkName = out.name,
            sourceCodec = src.codec,
            sourceSampleRate = src.sampleRate,
            sourceChannels = src.channelCount,
            sourceBitDepth = src.bitDepth,
            outputSampleRate = out.sampleRate,
            outputEncoding = out.outputEncoding,
            sharingMode = out.sharingMode,
            performanceMode = out.performanceMode,
            bufferFrames = out.bufferFrames,
            latencyMs = out.latencyMs,
            xRuns = out.xRuns(),
            bitPerfect = out.bitPerfect,
            resampled = out.sampleRate != src.sampleRate,
            message = message,
        )
    }

    private fun currentFramePosition(): Long {
        val out = output ?: return baseFrame
        val presented = try {
            out.positionFrames()
        } catch (_: Exception) {
            0L
        }
        return (baseFrame + presented).coerceAtLeast(0)
    }

    private fun framesToMs(frames: Long): Long =
        if (frameRate > 0) frames * 1000 / frameRate else 0L

    private fun msToFrames(ms: Long): Long =
        if (frameRate > 0) ms * frameRate / 1000 else 0L

    private fun fail(message: String) {
        lastError = message
        playing = false
        _state.value = PlaybackState.ERROR
        _events.tryEmit(EngineEvent.Error(message))
        publishDiagnostics()
    }

    private fun closeCurrent() {
        playing = false
        try {
            output?.close()
        } catch (_: Exception) {
            // ignore
        }
        try {
            source?.close()
        } catch (_: Exception) {
            // ignore
        }
        output = null
        source = null
        frameRate = 0
        totalFrames = 0
        baseFrame = 0
        chunk = FloatArray(0)
        chunkFrames = 0
        needsRefill = false
    }

    private sealed interface Command {
        data class Load(val uri: Uri, val displayName: String, val autoPlay: Boolean) : Command
        data object Play : Command
        data object Pause : Command
        data class Seek(val positionMs: Long) : Command
        data object Release : Command
    }
}
