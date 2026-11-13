package ru.tinyops.android.vboard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** After the app is updated, asks for the word pair table of the new version when the user had the old one. */
class WordPairsRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // A debug build carries its table in the APK and never downloads one.
        if (!WordPairs.isDownloadable(context)) return
        if (!WordPairs.needsRefresh(directory = WordPairs.directory(context))) return
        Log.i(TAG, "The word pair table belongs to the previous version; scheduling a download")
        val job =
            JobInfo
                .Builder(JOB_ID, ComponentName(context, WordPairsRefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(WordPairs.release.size, 0)
                .setPersisted(true)
                .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    private companion object {
        const val TAG = "WordPairsRefresh"
        const val JOB_ID = 7302
    }
}

/** Downloads the word pair table of this version in the background, as the settings page does on a tap. */
class WordPairsRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val directory = WordPairs.directory(this)
        if (!WordPairs.isDownloadable(this) || !WordPairs.needsRefresh(directory = directory)) return false
        WordPairsDownload.start(this)
        scope.launch {
            val result = WordPairsDownload.state.first { it !is WordPairsDownload.State.Running }
            val failed = result as? WordPairsDownload.State.Failed
            if (failed?.missing == true) {
                // The table of the previous version cannot serve this one, and this one has none to offer.
                WordPairs.delete(this@WordPairsRefreshJob)
            }
            jobFinished(params, failed != null && !failed.missing && !failed.noSpace)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        WordPairsDownload.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
