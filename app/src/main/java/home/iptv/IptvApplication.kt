package home.iptv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import home.iptv.core.database.*
import home.iptv.core.network.SourceLoader
import home.iptv.core.preferences.Preferences
import home.iptv.sync.*
import kotlinx.coroutines.*

class IptvApplication : Application(), SingletonImageLoader.Factory {
    val graph by lazy { AppGraph(this) }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Upstream playback exceptions may contain private stream URLs.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("logos").toOkioPath()).maxSizeBytes(32L * 1024 * 1024).build() }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
        .components { add(OkHttpNetworkFetcherFactory(cacheStrategy = { CacheControlCacheStrategy() })) }
        .crossfade(false).build()
}
class AppGraph(context: Context) {
    val db = IptvDatabase.open(context)
    val preferences = Preferences(context)
    val loader = SourceLoader(context)
    val importer = ImportCoordinator(db, loader)
    val scheduler = RefreshScheduler(context)
    val repository = Repository(db, importer, scheduler)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupJob: Job? = null
    private var lastStartupCheck = 0L
    @Synchronized fun onStart() {
        val checkTime = System.currentTimeMillis()
        if (startupJob?.isActive == true || checkTime - lastStartupCheck < 60_000L) return
        lastStartupCheck = checkTime
        startupJob = scope.launch {
            val now = System.currentTimeMillis()
            db.dao().prunePrograms(now - 86_400_000L)
            db.dao().sources().forEach { source ->
                scheduler.schedule(source)
                if (!source.snapshot && source.refreshOnStart && source.intervalHours > 0 && now - source.lastSuccess >= source.intervalHours * 3_600_000L) importer.refresh(source.id)
            }
        }
    }
}
