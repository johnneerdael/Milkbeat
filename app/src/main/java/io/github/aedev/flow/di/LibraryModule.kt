package io.github.aedev.flow.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.library.index.FolderLibrarySources
import io.github.aedev.flow.data.library.index.LibraryDao
import io.github.aedev.flow.data.library.index.LibraryDatabase
import io.github.aedev.flow.data.library.index.LibrarySources
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object LibraryModule {
    @Provides
    @Singleton
    fun provideLibraryDatabase(
        @ApplicationContext context: Context,
    ): LibraryDatabase = LibraryDatabase.create(context)

    @Provides
    fun provideLibraryDao(database: LibraryDatabase): LibraryDao = database.dao()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LibrarySourcesModule {
    @Binds
    abstract fun bindLibrarySources(sources: FolderLibrarySources): LibrarySources
}
