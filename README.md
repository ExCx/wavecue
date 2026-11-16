# WaveCue

Android app for producers who work with long, continuous recordings. Open a folder of
WAV/MP3 album tracks, follow the waveform while zooming and dragging, flag the quiet
sections, and keep timecoded notes against the audio.

Package: `com.wavecue.app` · minSdk 26 · targetSdk 36 · Kotlin + Jetpack Compose ·
native audio core in C++ (AAudio) · no `INTERNET` permission.

## Features

- Folder browser over the Storage Access Framework; only playable audio is listed.
- Persistent playlists with natural (track-number aware) alphabetical order and a
  one-tap randomize. Playing a track outside a playlist builds an ephemeral playlist
  from the current folder instead of writing to the database.
- Multi-resolution waveform with pinch zoom (up to 1024×), drag navigation, and an
  optional follow-playhead mode.
- Transport is Play/Pause only (no Stop button): previous restarts the current track
  when it is past 3 s, otherwise steps back one queue entry. Next/previous and ±5 s
  skip buttons.
- Silence detection over the RMS envelope with configurable threshold, minimum
  duration, edge handling, and transient merging. Results are reviewed and selected
  before any flag is created.
- Flags per track: create, retitle, move in time, delete. Flags can be auto-created
  from accepted silence regions.
- Timecoded notes: create at the playhead or at an explicit timecode, edit body and
  time, delete. Stored in the local Room database (no sidecar files).
- Export: full-library JSON plus a per-track Markdown notes file, written through the
  system `CreateDocument` picker.

## Build

Requirements: JDK 21, Android SDK with platform 36, build-tools 36.1.0,
NDK 27.3.13750724, CMake 3.22.1. Point `ANDROID_HOME`/`ANDROID_SDK_ROOT` at the SDK
(or keep `local.properties` up to date), then:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified, unsigned
./gradlew test                 # JVM unit tests
./gradlew :app:lintDebug
```

The Gradle wrapper downloads Gradle 8.13. No Java toolchain is configured; the build
runs on the JDK that launches Gradle (21) and targets Java 17 bytecode.

## Modules

| Module | Contents |
| --- | --- |
| `:core:model` | Pure JVM domain types, natural ordering, timecode parsing/formatting. |
| `:core:audio` | Playback engine, WAV/MP3 PCM sources, AAudio + AudioTrack outputs, JNI. |
| `:core:analysis` | Waveform pyramid, on-disk cache, silence detector. |
| `:core:data` | Room database, SAF access, repositories, DataStore settings. |
| `:app` | Compose UI, playback service/media session, export, Hilt wiring. |

## Audio path

- Everything is decoded to interleaved float on a dedicated engine thread. WAV is read
  by a streaming RIFF/RF64/BW64 parser (PCM 8/16/24/32-bit and 32-bit float,
  `WAVE_FORMAT_EXTENSIBLE`); MP3 is decoded by `MediaCodec`, and seeks are trimmed
  against presentation timestamps.
- Output prefers a USB bit-perfect `AudioTrack` stream on Android 14+ when the device
  advertises a matching `AudioMixerAttributes` (same rate, channel mask, encoding);
  otherwise an AAudio stream, otherwise a shared float `AudioTrack`. The app never
  ducks or applies DSP, volume stays at unity in bit-perfect mode, and the diagnostics
  panel reports exactly which path is active, including any system resampling.
- Playback runs in a foreground service with a `MediaSessionCompat` so notification and
  lockscreen controls map to the same Play/Pause toggle. Stop is intentionally absent.

## Tests

- `:core:model` — natural ordering, timecode format/parse round-trips (17 tests).
- `:core:audio` — WAV header parser across PCM, extensible, float, RF64, odd chunks,
  and malformed input (12 tests).
- `:core:analysis` — waveform pyramid levels, level selection, silence thresholds, edge
  inclusion, gap merging (16 tests).

## Known limitations

- The engine is float based. 32-bit integer WAV sources keep ~24 bits of mantissa, so
  the lowest 8 bits are lost in the internal representation (16/24-bit and float
  sources are exact).
- The USB bit-perfect path supports 16-bit, 24-bit packed, and 32-bit float sources.
  8-bit PCM and 32-bit integer PCM fall back to the shared path.
- `MediaCodec` MP3 output rate/channel count can differ from the container; the engine
  probes the decoder before creating the sink so the reported format is the real one.
- Runtime audio behaviour has not been verified on physical hardware in this
  environment. A headless emulator was attempted, but this machine does not grant the
  build user access to `/dev/kvm`, so it could not boot.

## On-device verification checklist

1. Grant folder access; confirm listing and natural order.
2. Play a 16-bit WAV and an MP3; check seek accuracy, ±5 s, and next/previous.
3. Lock the device and exercise notification/lockscreen Play/Pause and next/previous.
4. Run a silence scan, deselect regions, accept the rest, then move and delete flags.
5. Add/edit/delete notes, export Markdown and JSON, and re-import the JSON manually.
6. With a USB DAC on Android 14+, confirm the diagnostics show the bit-perfect path;
   unplug it and confirm playback falls back without a crash.

## Release checklist

- Add a signing config (keystore is not committed).
- Confirm the Play Store listing name; "Wavecue" is used by an existing Windows
  soundboard app, so a trademark/name check is recommended before publishing.
