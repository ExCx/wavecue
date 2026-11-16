package com.wavecue.core.audio

class AaudioOutput private constructor(
    private val sink: NativeAudioSink,
    override val sampleRate: Int,
    override val channelCount: Int,
) : AudioOutput {

    override val name: String = "AAudio"
    override val bufferFrames: Int = sink.bufferFrames()
    override val capacityFrames: Int = sink.capacityFrames()
    override val latencyMs: Double = if (sampleRate > 0) bufferFrames * 1000.0 / sampleRate else 0.0
    override val sharingMode: String = NativeAudioSink.sharingName(sink.sharingMode())
    override val performanceMode: String = NativeAudioSink.performanceName(sink.performanceMode())
    override val outputEncoding: String = "float32"
    override val bitPerfect: Boolean = false

    override fun start() {
        sink.start()
    }

    override fun pause() {
        sink.pause()
    }

    override fun flush() {
        sink.flush()
    }

    override fun write(samples: FloatArray, sampleOffset: Int, sampleCount: Int): Int =
        sink.write(samples, sampleOffset, sampleCount)

    override fun positionFrames(): Long = sink.positionFrames()
    override fun bufferedFrames(): Int = sink.bufferedFrames()
    override fun xRuns(): Int = sink.xRuns()
    override fun isDisconnected(): Boolean = sink.isDisconnected()

    override fun close() {
        sink.close()
    }

    companion object {
        fun create(sampleRate: Int, channels: Int, exclusive: Boolean): AaudioOutput? {
            val sink = NativeAudioSink.create(sampleRate, channels, exclusive) ?: return null
            val actualRate = sink.sampleRate()
            val actualChannels = sink.channels()
            if (actualRate <= 0 || actualChannels <= 0) {
                sink.close()
                return null
            }
            return AaudioOutput(sink, actualRate, actualChannels)
        }
    }
}
