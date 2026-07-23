package org.fossify.clock.services

import android.app.job.JobParameters
import android.app.job.JobService
import org.fossify.clock.helpers.CalendarAlarmSync
import org.fossify.clock.helpers.CalendarSyncScheduler
import kotlin.concurrent.thread

class CalendarSyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "tclock-calendar-sync") {
            CalendarAlarmSync.sync(applicationContext)
            if (
                params.jobId == CalendarSyncScheduler.CONTENT_JOB_A ||
                params.jobId == CalendarSyncScheduler.CONTENT_JOB_B
            ) {
                CalendarSyncScheduler.scheduleContentWatch(
                    applicationContext,
                    CalendarSyncScheduler.nextContentJobId(params.jobId)
                )
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
