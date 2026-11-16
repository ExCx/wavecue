package com.wavecue.app.di

import android.content.Context
import com.wavecue.core.audio.PlaybackEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun playbackEngine(@ApplicationContext context: Context): PlaybackEngine =
        PlaybackEngine(context)
}
