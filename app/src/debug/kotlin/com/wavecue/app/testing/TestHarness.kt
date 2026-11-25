package com.wavecue.app.testing

import android.content.Context
import com.wavecue.app.playback.PlaybackController
import com.wavecue.core.data.db.RootDao
import com.wavecue.core.data.db.TrackDao
import com.wavecue.core.data.db.WaveCueDatabase
import com.wavecue.core.data.repo.AnalysisRepository
import com.wavecue.core.data.repo.SettingsRepository
import com.wavecue.core.model.PlaybackState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WaveCueTestEntryPoint {
    fun database(): WaveCueDatabase
    fun rootDao(): RootDao
    fun trackDao(): TrackDao
    fun settings(): SettingsRepository
    fun analysis(): AnalysisRepository
    fun controller(): PlaybackController
}

/**
 * Debug-only state control for instrumented tests. It never ships in a release
 * build; the release variant has no such class on its classpath.
 *
 * The tests still drive every user-facing screen by tapping the real UI. This
 * only replaces the parts a human would do outside the app: starting from a
 * known database and preference state, and not inheriting the previous test's
 * queue.
 */
object TestHarness {

    private fun entry(context: Context): WaveCueTestEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WaveCueTestEntryPoint::class.java,
        )

    /**
     * Empties flags, notes, playlists, tracks and the waveform cache, then puts
     * the library roots back so the persisted SAF grants stay in use and the
     * folder picker only has to run once per install.
     */
    fun reset(context: Context) {
        val e = entry(context)
        runBlocking(Dispatchers.IO) {
            e.controller().clearQueue()
            e.analysis().clearCache()
            val roots = e.rootDao().all()
            e.database().clearAllTables()
            roots.forEach { e.rootDao().insert(it) }
            e.settings().resetToDefaults()
        }
    }

    fun rootUris(context: Context): List<String> = runBlocking(Dispatchers.IO) {
        entry(context).rootDao().all().map { it.treeUri }
    }

    fun rootNames(context: Context): List<String> = runBlocking(Dispatchers.IO) {
        entry(context).rootDao().all().map { it.name }
    }

    fun trackNames(context: Context): List<String> = runBlocking(Dispatchers.IO) {
        entry(context).trackDao().all().map { it.name }
    }

    fun flagTitles(context: Context): List<String> = runBlocking(Dispatchers.IO) {
        entry(context).database().flagDao().all().map { it.title }
    }

    fun noteBodies(context: Context): List<String> = runBlocking(Dispatchers.IO) {
        entry(context).database().noteDao().all().map { it.body }
    }

    fun isPlaying(context: Context): Boolean =
        entry(context).controller().state.value == PlaybackState.PLAYING

    fun currentTrackName(context: Context): String? =
        entry(context).controller().current.value?.name
}
