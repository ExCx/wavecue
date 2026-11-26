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

### Unit tests

| Module | Coverage | Count |
| --- | --- | --- |
| `:core:model` | Natural ordering, timecode format/parse round-trips | 17 |
| `:core:audio` | WAV header parser across PCM, extensible, float, RF64, odd chunks, malformed input | 12 |
| `:core:analysis` | Waveform pyramid levels, level selection, silence thresholds, edge inclusion, gap merging | 16 |

```bash
./gradlew test                          # every JVM test task
./gradlew :app:lintDebug                # lint across app and core modules
```

### Instrumented functional tests

`app/src/androidTest/.../qa/` drives the installed app the way a person does: the real
`MainActivity`, real taps and swipes, the real DocumentsUI picker and save dialog, the
real notification shade and media session. No test calls a ViewModel to fabricate a
result; assertions read the UI or the database after the human path ran.

| Class | Flow exercised |
| --- | --- |
| `LibraryBrowserTest` | Picks a folder through DocumentsUI, checks only WAV/MP3 are listed in natural order, opens a track |
| `PlaybackTest` | Play/pause holds the playhead, ±5 s steps, Previous restart-vs-step semantics, queue advance, auto-advance at end of track |
| `WaveformNavigationTest` | Zoom in/out, pan at 8×, Fit, tap-to-seek on the waveform |
| `SilenceDetectionTest` | Detects the exact 12–17 s gap, cancels without storing, then accepts it as an automatic flag |
| `AnnotationsTest` | Flag create/edit/delete with an editable timecode, survives activity recreation, note create |
| `PlaylistTest` | Create a playlist, add tracks, verify A–Z order, shuffle and restore, move/remove entries, delete playlist |
| `ExportTest` | Exports JSON and Markdown, verifies their contents, confirms nothing is written beside the audio |
| `NotificationTest` | Notification shows the track, media-session state, hardware play/pause keys |
| `SettingsLifecycleTest` | A preference survives recreation, playback survives backgrounding |

Fixtures are deterministic and regenerable:

```bash
python3 scripts/qa/make_fixtures.py --out build/qa-fixtures
```

| File | Ground truth |
| --- | --- |
| `01 Opening.wav` | 32 s, 48 kHz, 16-bit stereo; silence at 0–5 s, 12–17 s, 27–32 s |
| `02 Middle.wav` | 10 s continuous tone |
| `03 Finale.wav` | 3 s continuous tone, short enough to auto-advance |
| `04 Codec Test.mp3` | 5.256 s MPEG-2.5 Layer III, 8 kHz mono (CC0 silence) |
| `readme.txt`, `artwork.jpg`, `05 Not Supported.m4a` | must never be listed |

Silence boundaries are multiples of the 64-frame analysis bin, so the detector returns
exactly `(12000, 17000)` and the test can assert `0:12.000 → 0:17.000` and the flag at
`0:14.500`.

Folder access is persisted SAF state, so the suite is hybrid: `LibraryBrowserTest` runs
the genuine DocumentsUI picker once per install, and a debug-only `TestHarness` clears
the database, preferences and playback queue before each test while restoring the
library roots. Later tests reuse the grant; every user-facing screen is still driven by
real taps. The harness and its Hilt entry point exist only in the `debug` variant.

Run it against a booted device or emulator:

```bash
adb shell mkdir -p /sdcard/WaveCueFixtures
adb push build/qa-fixtures/. /sdcard/WaveCueFixtures/
./gradlew connectedDebugAndroidTest
```

An emulator needs `/dev/kvm`. On machines where the build user is not in the `kvm`
group, `scripts/qa/run_local_emulator.sh` falls back to software emulation and does the
whole sequence (boot, unlock, wait for the FUSE `/sdcard`, seed fixtures, run the suite):

```bash
SLOW=1 ./scripts/qa/run_local_emulator.sh   # or omit SLOW when /dev/kvm works
```

The script waits for the primary user to unlock and for the FUSE `/sdcard` mount to be
listable before pushing fixtures, because a software-emulated device can take minutes
to reach that point. Audio fidelity itself cannot be judged from an emulator without
USB hardware, so the instrumented suite asserts UI state, timing, media-session state
and logcat health.

### Exploratory adb harness

`scripts/qa/wavecue_qa.py` is the interactive half: it installs the debug APK, pushes
fixtures, launches the app, reads the real accessibility tree, taps coordinates and
captures evidence, all through `adb`. Use it to reproduce a report or to prototype a
new committed test.

```bash
python3 scripts/qa/wavecue_qa.py setup                 # build fixtures, install, push, launch
python3 scripts/qa/wavecue_qa.py dump                  # accessibility tree
python3 scripts/qa/wavecue_qa.py tap "Choose a folder"
python3 scripts/qa/wavecue_qa.py pick-folder WaveCueFixtures
python3 scripts/qa/wavecue_qa.py media                 # dumpsys media_session slice
python3 scripts/qa/wavecue_qa.py shot library          # build/qa-evidence/<run>/library.png
```

`--adb` and `--serial` select the binary and device when more than one is attached.

### CI

`.github/workflows/android.yml` has two jobs. The build job assembles the debug APK,
runs all JVM tests and lint. The instrumented job boots an API 34 `google_apis` x86_64
emulator with the standard KVM udev rule, waits for the FUSE-backed `/sdcard`,
pushes the generated fixtures and runs `connectedDebugAndroidTest`, then uploads the
test reports and APK artifacts. `scripts/qa/summarize_results.py` turns each failed
JUnit case into a GitHub check annotation, so a red run can be diagnosed from the
checks API without downloading the report or the raw job log.

## Known limitations

- The engine is float based. 32-bit integer WAV sources keep ~24 bits of mantissa, so
  the lowest 8 bits are lost in the internal representation (16/24-bit and float
  sources are exact).
- The USB bit-perfect path supports 16-bit, 24-bit packed, and 32-bit float sources.
  8-bit PCM and 32-bit integer PCM fall back to the shared path.
- `MediaCodec` MP3 output rate/channel count can differ from the container; the engine
  probes the decoder before creating the sink so the reported format is the real one.
- Runtime audio behaviour has not been verified on physical hardware in this
  environment. A local headless emulator needs the build user in the `kvm` group; that
  has not been granted on this workstation, so the instrumented suite runs in CI. Even
  on an emulator, bit-perfect output can only be verified with a real USB DAC.

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
