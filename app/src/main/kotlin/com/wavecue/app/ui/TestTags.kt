package com.wavecue.app.ui

/**
 * Stable handles for instrumented tests. Only elements whose text or content
 * description is ambiguous (or that carry no label at all) need one.
 */
object TestTags {
    const val NavLibrary = "nav.library"
    const val NavPlaylists = "nav.playlists"
    const val NavPlayer = "nav.player"
    const val NavSettings = "nav.settings"

    const val NowPlayingToggle = "nowplaying.toggle"
    const val NowPlayingNext = "nowplaying.next"

    const val PlayerWaveform = "player.waveform"
    const val PlayerPosition = "player.position"
    const val PlayerDuration = "player.duration"

    const val TransportToggle = "transport.toggle"
    const val TransportPrevious = "transport.previous"
    const val TransportNext = "transport.next"
    const val TransportBack5 = "transport.back5"
    const val TransportForward5 = "transport.forward5"

    const val ZoomIn = "waveform.zoomIn"
    const val ZoomOut = "waveform.zoomOut"
    const val ZoomFit = "waveform.fit"
    const val ZoomLabel = "waveform.zoomLabel"
    const val ViewportStart = "waveform.viewportStart"

    const val FlagTitle = "flag.title"
    const val FlagTime = "flag.time"
    const val FlagSave = "flag.save"
    const val FlagDelete = "flag.delete"

    const val NoteBody = "note.body"
    const val NoteTime = "note.time"
    const val NoteSave = "note.save"
    const val NoteDelete = "note.delete"

    const val SilenceCreate = "silence.create"

    const val SettingsExclusive = "settings.exclusive"
    const val SettingsBitPerfect = "settings.bitPerfect"
    const val SettingsFollowPlayhead = "settings.followPlayhead"
    const val SettingsIncludeEdges = "settings.includeEdges"

    fun libraryTrack(name: String) = "library.track.$name"
    fun libraryTrackMenu(name: String) = "library.menu.$name"
}
