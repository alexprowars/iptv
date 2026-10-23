package home.iptv.sync

import android.content.Context
import android.net.Uri
import androidx.work.*
import home.iptv.IptvApplication
import home.iptv.core.model.Source
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("source") ?: return Result.failure()
        val graph = (applicationContext as IptvApplication).graph
        if (graph.importer.refresh(id)) return Result.success()
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

class RefreshScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)
    fun schedule(source: Source) {
        if (source.intervalHours == 0 || source.snapshot) { cancel(source.id); return }
        val network = Uri.parse(source.location).scheme in listOf("http", "https")
        val constraints = Constraints.Builder().apply { if (network) setRequiredNetworkType(NetworkType.CONNECTED) }.build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(source.intervalHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(source.intervalHours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf("source" to source.id)).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        work.enqueueUniquePeriodicWork("source-${source.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun cancel(id: String) { work.cancelUniqueWork("source-$id") }
}
