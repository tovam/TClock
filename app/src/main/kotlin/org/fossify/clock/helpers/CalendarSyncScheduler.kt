package org.fossify.clock.helpers

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import org.fossify.clock.services.CalendarSyncJobService
import java.util.concurrent.TimeUnit

object CalendarSyncScheduler {
    const val CONTENT_JOB_A = 7211
    const val CONTENT_JOB_B = 7212
    const val PERIODIC_JOB = 7213

    fun schedule(context: Context) {
        if (!CalendarAlarmSync.hasCalendarPermission(context)) {
            cancel(context)
            return
        }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val hasContentJob = scheduler.allPendingJobs.any {
            it.id == CONTENT_JOB_A || it.id == CONTENT_JOB_B
        }
        if (!hasContentJob) {
            scheduleContentWatch(context, CONTENT_JOB_A)
        }
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
            val component = ComponentName(context, CalendarSyncJobService::class.java)
            val periodicJob = JobInfo.Builder(PERIODIC_JOB, component)
                .setPeriodic(TimeUnit.HOURS.toMillis(12))
                .build()
            scheduler.schedule(periodicJob)
        }
    }

    fun scheduleContentWatch(context: Context, jobId: Int) {
        if (!CalendarAlarmSync.hasCalendarPermission(context)) {
            return
        }
        val component = ComponentName(context, CalendarSyncJobService::class.java)
        val trigger = JobInfo.TriggerContentUri(
            CalendarContract.CONTENT_URI,
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
        )
        val job = JobInfo.Builder(jobId, component)
            .addTriggerContentUri(trigger)
            .setTriggerContentUpdateDelay(1_000L)
            .setTriggerContentMaxDelay(10_000L)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).apply {
            cancel(CONTENT_JOB_A)
            cancel(CONTENT_JOB_B)
            cancel(PERIODIC_JOB)
        }
    }

    fun nextContentJobId(currentJobId: Int): Int {
        return if (currentJobId == CONTENT_JOB_A) CONTENT_JOB_B else CONTENT_JOB_A
    }
}
