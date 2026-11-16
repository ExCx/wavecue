#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "WaveCueAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kRingSeconds = 2;
constexpr int32_t kDefaultCapacityFrames = 4096;
constexpr int32_t kWriteTimeoutMs = 200;

struct AudioSink {
    AAudioStream *stream = nullptr;
    int32_t channels = 2;
    int32_t sampleRate = 0;

    std::vector<float> ring;
    int32_t capacityFrames = 0;

    std::atomic<uint64_t> writeFrames{0};
    std::atomic<uint64_t> readFrames{0};
    std::atomic<uint64_t> deliveredFrames{0};
    std::atomic<int64_t> positionFrames{0};
    std::atomic<uint32_t> xRuns{0};
    std::atomic<bool> disconnected{false};
    std::atomic<bool> closing{false};

    std::mutex spaceMutex;
    std::condition_variable spaceAvailable;

    std::vector<float> scratch;
};

int32_t framesAvailable(const AudioSink *sink) {
    const uint64_t w = sink->writeFrames.load(std::memory_order_acquire);
    const uint64_t r = sink->readFrames.load(std::memory_order_acquire);
    if (w < r) return 0;
    const uint64_t diff = w - r;
    return diff > static_cast<uint64_t>(sink->capacityFrames)
           ? sink->capacityFrames
           : static_cast<int32_t>(diff);
}

aaudio_data_callback_result_t dataCallback(
        AAudioStream *stream,
        void *userData,
        void *audioData,
        int32_t numFrames) {
    auto *sink = static_cast<AudioSink *>(userData);
    auto *out = static_cast<float *>(audioData);

    const int32_t available = framesAvailable(sink);
    const int32_t toCopy = std::min(available, numFrames);

    if (toCopy > 0) {
        const uint64_t read = sink->readFrames.load(std::memory_order_relaxed);
        const size_t cap = static_cast<size_t>(sink->capacityFrames);
        const size_t start = static_cast<size_t>(read % cap);
        const size_t first = std::min(static_cast<size_t>(toCopy), cap - start);
        std::memcpy(out,
                    sink->ring.data() + start * sink->channels,
                    first * sink->channels * sizeof(float));
        if (first < static_cast<size_t>(toCopy)) {
            std::memcpy(out + first * sink->channels,
                        sink->ring.data(),
                        (static_cast<size_t>(toCopy) - first) * sink->channels * sizeof(float));
        }
        sink->readFrames.store(read + static_cast<uint64_t>(toCopy), std::memory_order_release);
    }

    if (toCopy < numFrames) {
        std::memset(out + static_cast<size_t>(toCopy) * sink->channels,
                    0,
                    static_cast<size_t>(numFrames - toCopy) * sink->channels * sizeof(float));
        sink->xRuns.fetch_add(1, std::memory_order_relaxed);
    }

    const uint64_t delivered =
            sink->deliveredFrames.fetch_add(static_cast<uint64_t>(numFrames), std::memory_order_release) +
            static_cast<uint64_t>(numFrames);

    // Approximate the presented position by subtracting the stream buffer. This is
    // monotonic, cheap, and accurate to a few milliseconds, which is enough for the
    // playhead. Seeking itself remains sample-accurate.
    int32_t bufferFrames = AAudioStream_getBufferSizeInFrames(stream);
    if (bufferFrames < 0) bufferFrames = 0;
    const int64_t presented = static_cast<int64_t>(delivered) - bufferFrames;
    sink->positionFrames.store(presented > 0 ? presented : 0, std::memory_order_release);

    sink->spaceAvailable.notify_one();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void errorCallback(AAudioStream *stream, void *userData, aaudio_result_t error) {
    auto *sink = static_cast<AudioSink *>(userData);
    if (sink != nullptr) {
        sink->disconnected.store(true, std::memory_order_release);
    }
    LOGE("AAudio error callback: %s", AAudio_convertResultToText(error));
}

void waitForState(AAudioStream *stream, aaudio_stream_state_t currentState, int64_t timeoutNanos) {
    aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
    AAudioStream_waitForStateChange(stream, currentState, &nextState, timeoutNanos);
}

AAudioStream *openStream(int32_t sampleRate, int32_t channels, bool exclusive, AudioSink *sink) {
    for (int pass = 0; pass < 2; ++pass) {
        const bool wantExclusive = exclusive && pass == 0;
        AAudioStreamBuilder *builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
            return nullptr;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate);
        AAudioStreamBuilder_setChannelCount(builder, channels);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(
                builder,
                wantExclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, kDefaultCapacityFrames);
        AAudioStreamBuilder_setDataCallback(builder, dataCallback, sink);
        AAudioStreamBuilder_setErrorCallback(builder, errorCallback, sink);

        AAudioStream *stream = nullptr;
        const aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);

        if (result == AAUDIO_OK && stream != nullptr) {
            const int32_t actualRate = AAudioStream_getSampleRate(stream);
            const int32_t actualChannels = AAudioStream_getChannelCount(stream);
            if (actualRate == sampleRate && actualChannels == channels) {
                const int32_t burst = AAudioStream_getFramesPerBurst(stream);
                const int32_t target = std::min(kDefaultCapacityFrames, std::max(burst * 2, 512));
                AAudioStream_setBufferSizeInFrames(stream, target);
                LOGI("AAudio opened: %d Hz, %d ch, %s", actualRate, actualChannels,
                     AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE
                     ? "exclusive" : "shared");
                return stream;
            }
            LOGE("AAudio rate/channel mismatch: requested %d Hz/%d ch, got %d Hz/%d ch",
                 sampleRate, channels, actualRate, actualChannels);
            AAudioStream_close(stream);
        } else {
            LOGE("AAudio open failed (pass %d): %s", pass, AAudio_convertResultToText(result));
        }
    }
    return nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeCreate(
        JNIEnv *env, jobject thiz, jint sampleRate, jint channels, jboolean exclusive) {
    if (sampleRate <= 0 || channels <= 0) return 0;

    auto *sink = new AudioSink();
    sink->sampleRate = sampleRate;
    sink->channels = channels;
    sink->capacityFrames = sampleRate * kRingSeconds;
    sink->ring.assign(static_cast<size_t>(sink->capacityFrames) * channels, 0.0f);

    AAudioStream *stream = openStream(sampleRate, channels, exclusive, sink);
    if (stream == nullptr) {
        delete sink;
        return 0;
    }
    sink->stream = stream;
    return reinterpret_cast<jlong>(sink);
}

JNIEXPORT jboolean JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeStart(JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return JNI_FALSE;
    const aaudio_result_t result = AAudioStream_requestStart(sink->stream);
    return result == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativePause(JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return;
    AAudioStream_requestPause(sink->stream);
    waitForState(sink->stream, AAUDIO_STREAM_STATE_PAUSING, 300 * 1000000LL);
}

JNIEXPORT void JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeFlush(JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return;

    AAudioStream_requestPause(sink->stream);
    waitForState(sink->stream, AAUDIO_STREAM_STATE_PAUSING, 300 * 1000000LL);
    AAudioStream_requestFlush(sink->stream);
    waitForState(sink->stream, AAUDIO_STREAM_STATE_FLUSHING, 300 * 1000000LL);

    sink->writeFrames.store(0, std::memory_order_release);
    sink->readFrames.store(0, std::memory_order_release);
    sink->deliveredFrames.store(0, std::memory_order_release);
    sink->positionFrames.store(0, std::memory_order_release);
    sink->xRuns.store(0, std::memory_order_relaxed);
    sink->spaceAvailable.notify_all();
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeWrite(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray data, jint sampleOffset, jint sampleCount) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sampleCount <= 0 || data == nullptr) return 0;
    if (sampleOffset < 0 || static_cast<jint>(env->GetArrayLength(data)) < sampleOffset + sampleCount) return 0;

    sink->scratch.resize(static_cast<size_t>(sampleCount));
    env->GetFloatArrayRegion(data, sampleOffset, sampleCount, sink->scratch.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }

    const int32_t frames = sampleCount / sink->channels;
    if (frames <= 0) return 0;

    std::unique_lock<std::mutex> lock(sink->spaceMutex);
    sink->spaceAvailable.wait_for(
            lock,
            std::chrono::milliseconds(kWriteTimeoutMs),
            [sink, frames] {
                return sink->closing.load(std::memory_order_acquire) ||
                       sink->capacityFrames - framesAvailable(sink) >= frames;
            });

    if (sink->closing.load(std::memory_order_acquire)) return 0;

    const int32_t space = sink->capacityFrames - framesAvailable(sink);
    if (space <= 0) return 0;
    const int32_t toWrite = std::min(space, frames);

    const uint64_t write = sink->writeFrames.load(std::memory_order_relaxed);
    const size_t cap = static_cast<size_t>(sink->capacityFrames);
    const size_t start = static_cast<size_t>(write % cap);
    const size_t first = std::min(static_cast<size_t>(toWrite), cap - start);

    std::memcpy(sink->ring.data() + start * sink->channels,
                sink->scratch.data(),
                first * sink->channels * sizeof(float));
    if (first < static_cast<size_t>(toWrite)) {
        std::memcpy(sink->ring.data(),
                    sink->scratch.data() + first * sink->channels,
                    (static_cast<size_t>(toWrite) - first) * sink->channels * sizeof(float));
    }
    sink->writeFrames.store(write + static_cast<uint64_t>(toWrite), std::memory_order_release);
    return static_cast<jint>(toWrite) * sink->channels;
}

JNIEXPORT void JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr) return;

    sink->closing.store(true, std::memory_order_release);
    sink->spaceAvailable.notify_all();

    if (sink->stream != nullptr) {
        AAudioStream_requestStop(sink->stream);
        waitForState(sink->stream, AAUDIO_STREAM_STATE_STOPPING, 300 * 1000000LL);
        AAudioStream_close(sink->stream);
        sink->stream = nullptr;
    }
    delete sink;
}

JNIEXPORT jlong JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativePositionFrames(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr) return 0;
    return sink->positionFrames.load(std::memory_order_acquire);
}

JNIEXPORT jlong JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeDeliveredFrames(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr) return 0;
    return sink->deliveredFrames.load(std::memory_order_acquire);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeBufferedFrames(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr) return 0;
    return framesAvailable(sink);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetSampleRate(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getSampleRate(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetChannels(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getChannelCount(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetSharingMode(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getSharingMode(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetPerformanceMode(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getPerformanceMode(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetBufferFrames(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getBufferSizeInFrames(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetCapacityFrames(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getBufferCapacityInFrames(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetBurstFrames(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr || sink->stream == nullptr) return 0;
    return AAudioStream_getFramesPerBurst(sink->stream);
}

JNIEXPORT jint JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeGetXRunCount(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr) return 0;
    return static_cast<jint>(sink->xRuns.load(std::memory_order_relaxed));
}

JNIEXPORT jboolean JNICALL
Java_com_wavecue_core_audio_NativeAudioSink_nativeIsDisconnected(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto *sink = reinterpret_cast<AudioSink *>(handle);
    if (sink == nullptr) return JNI_TRUE;
    return sink->disconnected.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
