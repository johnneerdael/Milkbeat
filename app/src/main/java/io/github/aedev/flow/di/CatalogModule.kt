package io.github.aedev.flow.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.catalog.CatalogRouter
import nl.neerdael.milkbeat.catalog.MetadataProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogModule {
    @Binds
    abstract fun bindMusicProvider(router: CatalogRouter): MetadataProvider

    @Binds
    abstract fun bindMusicPlayback(router: CatalogRouter): CatalogPlayback
}
