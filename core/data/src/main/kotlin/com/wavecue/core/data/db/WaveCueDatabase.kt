package com.wavecue.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.wavecue.core.model.PlaylistSortMode

class Converters {
    @TypeConverter
    fun fromSortMode(mode: PlaylistSortMode): String = mode.name

    @TypeConverter
    fun toSortMode(value: String): PlaylistSortMode =
        runCatching { PlaylistSortMode.valueOf(value) }.getOrDefault(PlaylistSortMode.ALPHABETICAL)
}

@Database(
    entities = [
        LibraryRootEntity::class,
        TrackEntity::class,
        FlagEntity::class,
        NoteEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class WaveCueDatabase : RoomDatabase() {
    abstract fun rootDao(): RootDao
    abstract fun trackDao(): TrackDao
    abstract fun flagDao(): FlagDao
    abstract fun noteDao(): NoteDao
    abstract fun playlistDao(): PlaylistDao
}
