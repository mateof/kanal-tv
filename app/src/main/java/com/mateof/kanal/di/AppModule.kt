package com.mateof.kanal.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mateof.kanal.data.db.KanalDatabase
import com.mateof.kanal.data.net.HttpProvider
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
    fun database(@ApplicationContext context: Context): KanalDatabase =
        Room.databaseBuilder(context, KanalDatabase::class.java, KanalDatabase.NAME)
            // Everything here is a re-downloadable cache, so a schema change can
            // simply drop it instead of shipping a migration.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    /**
     * Channel logos are small but there are thousands of them, and providers
     * serve them from slow hosts; a generous disk cache is what keeps the
     * channel grid from flickering on every scroll.
     */
    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        http: HttpProvider
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient { http.client }
        .memoryCache {
            MemoryCache.Builder(context).maxSizePercent(0.20).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizeBytes(120L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
