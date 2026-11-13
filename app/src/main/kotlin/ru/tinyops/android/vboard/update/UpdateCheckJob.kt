package ru.tinyops.android.vboard.update

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.IOException

/** The background check: stores what it finds, which the settings root then shows. */
class UpdateCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        if (!UpdatePrefs(this).autoCheck) return false
        scope.launch {
            val failed =
                try {
                    UpdateCheck.check(this@UpdateCheckJob)
                    false
                } catch (failure: IOException) {
                    Log.w(TAG, "Background update check failed", failure)
                    true
                }
            jobFinished(params, failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "UpdateCheckJob"
    }
}
