package com.nuvio.tv.core.di

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.xmplayer.AndroidXmpAuthPersistence
import com.nuvio.tv.data.xmplayer.AndroidXmpSyncStorage
import com.nuvio.tv.data.xmplayer.OkHttpXmpEngine
import com.nuvio.tv.data.xmplayer.XmpApiClient
import com.nuvio.tv.data.xmplayer.XmpAuthRepository
import com.nuvio.tv.data.xmplayer.XmpAuthStore
import com.nuvio.tv.data.xmplayer.XmpBridge
import com.nuvio.tv.data.xmplayer.XmpBridgeClient
import com.nuvio.tv.data.xmplayer.XmpChangeStream
import com.nuvio.tv.data.xmplayer.XmpConfiguration
import com.nuvio.tv.data.xmplayer.XmpHttpClient
import com.nuvio.tv.data.xmplayer.XmpCatalogClient
import com.nuvio.tv.data.mediaserver.xmplayer.XmpServerProvider
import com.nuvio.tv.data.xmplayer.XmpProbeEngine
import com.nuvio.tv.data.xmplayer.XmpSyncRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** XMPlayer Sync API (https://xmplayer.app/developers.html). The client id is XMPLAYER_CLIENT_ID (local.properties). */
@Module
@InstallIn(SingletonComponent::class)
object XmpModule {
    @Provides
    @Singleton
    fun configuration(): XmpConfiguration = XmpConfiguration(
        clientId = BuildConfig.XMPLAYER_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME
    )

    @Provides
    @Singleton
    fun http(configuration: XmpConfiguration): XmpHttpClient = XmpHttpClient(
        XmpProbeEngine(
            OkHttpXmpEngine(
                OkHttpClient.Builder()
                    .socketFactory(NoDelaySocketFactory)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .build(),
                configuration
            )
        )
    )

    @Provides
    @Singleton
    fun authStore(persistence: AndroidXmpAuthPersistence, profileDataStore: ProfileDataStore): XmpAuthStore {
        val store = XmpAuthStore(persistence)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: XmpAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(http: XmpHttpClient, configuration: XmpConfiguration, store: XmpAuthStore) =
        XmpAuthRepository(http, configuration, store)

    @Provides
    @Singleton
    fun api(http: XmpHttpClient, auth: XmpAuthRepository, store: XmpAuthStore) = XmpApiClient(http, auth, store)

    /**
     * The profile's IPTV catalog (edited through the account's XMPlayer DVR agent) and its Cloud DVR recordings. Its own
     * connection: the agent can take seconds per answer, and sync (progress, scrobbles) must never queue behind it — the
     * sync client sends one request at a time. Same sign-in (tokens are shared through the auth repository).
     */
    @Provides
    @Singleton
    fun catalog(configuration: XmpConfiguration, auth: XmpAuthRepository, store: XmpAuthStore) = XmpCatalogClient(
        XmpApiClient(
            XmpHttpClient(
                XmpProbeEngine(
                    OkHttpXmpEngine(
                        OkHttpClient.Builder()
                            .socketFactory(NoDelaySocketFactory)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .callTimeout(75, TimeUnit.SECONDS)
                            .build(),
                        configuration
                    )
                )
            ),
            auth,
            store
        )
    )

    /** XMPlayer's calendar in Nuvio: the profile's bells + own events (and its recordings through the catalog's connection). */
    @Provides
    @Singleton
    fun calendar(api: XmpApiClient, catalog: XmpCatalogClient) = com.nuvio.tv.data.xmplayer.XmpCalendarClient(api, catalog)

    /** The Releases dataset every XMPlayer surface reads (public, no sign-in). */
    @Provides
    @Singleton
    fun releases() = com.nuvio.tv.data.xmplayer.XmpReleasesClient()

    /**
     * Trakt and Simkl through XMPlayer (the profile's own XMPlayer link; no provider key or token in Nuvio). Its own
     * connection, like the catalog: a link poll or a status read never waits behind sync.
     */
    @Provides
    @Singleton
    fun bridge(
        configuration: XmpConfiguration,
        auth: XmpAuthRepository,
        store: XmpAuthStore,
        factory: ProfileDataStoreFactory,
        profiles: ProfileManager
    ) = XmpBridge(
        factory = factory,
        activeProfileId = profiles.activeProfileId,
        authStore = store,
        auth = auth,
        client = XmpBridgeClient(
            XmpApiClient(
                XmpHttpClient(
                    XmpProbeEngine(
                        OkHttpXmpEngine(
                            OkHttpClient.Builder()
                                .socketFactory(NoDelaySocketFactory)
                                .connectTimeout(15, TimeUnit.SECONDS)
                                .readTimeout(30, TimeUnit.SECONDS)
                                .callTimeout(45, TimeUnit.SECONDS)
                                .build(),
                            configuration
                        )
                    )
                ),
                auth,
                store
            )
        ),
        configuration = configuration,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    /** XMPlayer as a Nuvio media server: the curated catalog aired by its ids, and the recordings. */
    @Provides
    @Singleton
    fun serverProvider(catalog: XmpCatalogClient, api: XmpApiClient, store: XmpAuthStore) = XmpServerProvider(catalog, api, store)

    /** The live stream holds one long response open: no call timeout, and a read timeout the keep-alives reset. */
    @Provides
    @Singleton
    fun changeStream(configuration: XmpConfiguration, auth: XmpAuthRepository, store: XmpAuthStore) = XmpChangeStream(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .build(),
        configuration, auth, store
    )

    @Provides
    @Singleton
    fun syncRepository(
        storage: AndroidXmpSyncStorage,
        auth: XmpAuthStore,
        api: XmpApiClient,
        stream: XmpChangeStream,
        profiles: ProfileManager
    ) = XmpSyncRepository(storage, auth, api, stream, profiles.activeProfileId, CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
