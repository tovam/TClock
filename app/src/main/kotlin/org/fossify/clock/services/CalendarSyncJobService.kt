package org.fossify.clock.services

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossify.clock.helpers.CalendarAlarmSync
import org.fossify.clock.helpers.CalendarSyncScheduler
import java.util.concurrent.ConcurrentHashMap

class CalendarSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = CalendarAlarmSync.sync(applicationContext)
                val isContentJob =
                    params.jobId == CalendarSyncScheduler.CONTENT_JOB_A ||
                        params.jobId == CalendarSyncScheduler.CONTENT_JOB_B
                if (
                    isContentJob &&
                    !result.permissionMissing &&
                    currentCoroutineContext().isActive
                ) {
                    CalendarSyncScheduler.scheduleContentWatch(
                        applicationContext,
                        CalendarSyncScheduler.nextContentJobId(params.jobId)
                    )
                }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (currentJob != null && runningJobs.remove(params.jobId, currentJob)) {
                    jobFinished(params, false)
                }
            }
        }
        runningJobs.put(params.jobId, task)?.cancel()
        task.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        runningJobs.clear()
        scope.cancel()
        super.onDestroy()
    }
}
