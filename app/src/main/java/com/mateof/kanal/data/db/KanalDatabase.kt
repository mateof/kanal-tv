package com.mateof.kanal.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        SeriesDetailEntity::class,
        EpgEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KanalDatabase : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun epg(): EpgDao

    companion object {
        const val NAME = "kanal.db"
    }
}
