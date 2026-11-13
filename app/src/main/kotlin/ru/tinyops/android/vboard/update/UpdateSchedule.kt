package ru.tinyops.android.vboard.update

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit

/** Keeps the periodic background check in line with the update settings. */
internal object UpdateSchedule {
    private const val JOB_ID = 7301

    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val prefs = UpdatePrefs(context)
        if (!prefs.autoCheck) {
            scheduler.cancel(JOB_ID)
            return
        }
        val interval = TimeUnit.HOURS.toMillis(prefs.interval.hours)
        if (scheduler.getPendingJob(JOB_ID)?.intervalMillis == interval) return
        val job =
            JobInfo
                .Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval)
                .setPersisted(true)
                .build()
        scheduler.schedule(job)
    }
}
