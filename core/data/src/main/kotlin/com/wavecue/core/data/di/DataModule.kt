package com.wavecue.core.data.di

import android.content.Context
import androidx.room.Room
import com.wavecue.core.data.db.FlagDao
import com.wavecue.core.data.db.NoteDao
import com.wavecue.core.data.db.PlaylistDao
import com.wavecue.core.data.db.RootDao
import com.wavecue.core.data.db.TrackDao
import com.wavecue.core.data.db.WaveCueDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WaveCueDatabase =
        Room.databaseBuilder(context, WaveCueDatabase::class.java, "wavecue.db")
            .build()

    @Provides
    fun rootDao(database: WaveCueDatabase): RootDao = database.rootDao()

    @Provides
    fun trackDao(database: WaveCueDatabase): TrackDao = database.trackDao()

    @Provides
    fun flagDao(database: WaveCueDatabase): FlagDao = database.flagDao()

    @Provides
    fun noteDao(database: WaveCueDatabase): NoteDao = database.noteDao()

    @Provides
    fun playlistDao(database: WaveCueDatabase): PlaylistDao = database.playlistDao()
}
